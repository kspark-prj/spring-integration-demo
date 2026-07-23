package com.example.integration.config;

import com.example.integration.dto.UserImportDto;
import com.example.integration.transformer.CsvTransformer;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.integration.file.filters.AcceptOnceFileListFilter;
import org.springframework.integration.file.filters.ChainFileListFilter;
import org.springframework.integration.file.splitter.FileSplitter;
import org.springframework.integration.jdbc.JdbcMessageHandler;
import org.springframework.integration.jdbc.JdbcPollingChannelAdapter;
import org.springframework.integration.sftp.dsl.Sftp;
import org.springframework.integration.sftp.filters.SftpPersistentAcceptOnceFileListFilter;
import org.springframework.integration.sftp.filters.SftpSimplePatternFileListFilter;
import org.springframework.integration.sftp.inbound.SftpInboundFileSynchronizer;
import org.springframework.integration.sftp.inbound.SftpInboundFileSynchronizingMessageSource;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.integration.metadata.SimpleMetadataStore;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * [요구사항 5, 6] PostgreSQL DB ➔ SFTP Export & SFTP ➔ PostgreSQL DB Import 설정
 * - JDBC Polling 및 DB 트랜잭션 제어를 활용한 안전한 데이터 추출.
 * - 추출된 데이터를 CSV 파일로 변환하여 SFTP로 원격 전송 및 상태 갱신.
 * - SFTP로 원격지 CSV 파일을 감시/다운로드하여 한 줄씩 파싱 후 PostgreSQL에 UPSERT 처리.
 */
@Configuration
public class DbIntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(DbIntegrationConfig.class);

    @Value("${demo.db.polling-rate-ms}")
    private long dbPollingRateMs;

    @Value("${demo.sftp.local-download-dir}")
    private String localDownloadDir;

    @Value("${demo.sftp.remote-watch-dir}")
    private String remoteWatchDir;

    @Value("${demo.sftp.remote-upload-dir}")
    private String remoteUploadDir;

    @Value("${demo.sftp.temp-file-suffix}")
    private String tempFileSuffix;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SessionFactory<SftpClient.DirEntry> sftpSessionFactory;

    @Autowired
    private CsvTransformer csvTransformer;

    /*
     * =========================================================================
     * [요구사항 5] DB ➔ SFTP Export 플로우
     * =========================================================================
     */

    /**
     * DB에서 PENDING(미처리) 데이터를 조회하는 JDBC Inbound Channel Adapter
     * - 중복 조회 방지 핵심 원리:
     *   1) Select SQL과 Update SQL을 쌍으로 지정합니다.
     *   2) Polling 시점에 Select SQL로 레코드를 조회한 뒤, 트랜잭션이 커밋되기 전에 즉시 Update SQL을 수행하여
     *      status를 'PROCESSING' 상태로 전환합니다.
     *   3) 이 전체 과정(Select -> Update)은 Poller의 트랜잭션 경계 내에서 원자적으로 처리되므로,
     *      다중 인스턴스 환경 혹은 잦은 폴링 시에도 다른 스레드가 동일한 데이터를 중복 조회하는 현상을 완벽히 차단합니다.
     */
    @Bean
    public MessageSource<Object> jdbcPollingChannelAdapter() {
        JdbcPollingChannelAdapter adapter = new JdbcPollingChannelAdapter(dataSource,
                "SELECT id, username, email FROM user_export WHERE status = 'PENDING'");
        
        // 행별 파라미터를 넘겨주기 위해 ':id' 바인딩을 활용합니다.
        // UPDATE 문의 경우 여러 행의 일괄 상태 변경을 위해 IN 구문을 사용하여 콤마 구분 플레이스홀더에 대응합니다.
        adapter.setUpdateSql("UPDATE user_export SET status = 'PROCESSING' WHERE id IN (:id)");
        adapter.setRowMapper((rs, rowNum) -> Map.of(
                "id", rs.getInt("id"),
                "username", rs.getString("username"),
                "email", rs.getString("email")
        ));
        
        // 한 번에 폴링할 최대 행 개수 제한
        adapter.setMaxRows(100);
        return adapter;
    }

    /**
     * DB 조회 ➔ CSV 파일 변환 ➔ SFTP 업로드 ➔ 최종 상태 업데이트 통합 Flow
     */
    @Bean
    public IntegrationFlow dbToSftpExportFlow() {
        return IntegrationFlow.from(jdbcPollingChannelAdapter(),
                        // 트랜잭션 매니저를 폴러에 등록하여 Select & UpdateSql이 하나의 트랜잭션으로 묶이게 함.
                        c -> c.poller(Pollers.fixedDelay(Duration.ofMillis(dbPollingRateMs))
                                .transactional(transactionManager)))
                // 폴링된 결과가 있는 경우만 흐름 진행 (List가 비어있지 않은지 검증)
                .filter((List<?> list) -> !list.isEmpty())
                // Payload는 List<Map<String, Object>> 형태입니다.
                // 1. DTO/Map 데이터를 임시 CSV 파일로 생성 및 Message Header에 대상 ID 목록 보관
                .handle((PayloadType, headers) -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) PayloadType;
                    
                    // 파일 전송 성공 후 DB 상태를 'PROCESSED'로 올리기 위해 ID 리스트를 추출하여 헤더에 담음
                    List<Integer> ids = rows.stream()
                            .map(row -> (Integer) row.get("id"))
                            .collect(Collectors.toList());

                    File csvFile = csvTransformer.convertToCsvFile(rows);

                    return MessageBuilder.withPayload(csvFile)
                            .copyHeaders(headers)
                            .setHeader("export_ids", ids)
                            .setHeader("local_file_path", csvFile.getAbsolutePath()) // 로컬 임시 파일 경로를 헤더에 보관
                            .build();
                })
                // 2. 임시 CSV 파일을 SFTP 원격 서버에 안전하게 업로드 (Gateway PUT 커맨드 활용)
                // - Sftp.outboundGateway는 업로드가 완료된 후 원격 파일 경로를 리턴하므로,
                //   단방향 어댑터와 달리 뒤이어 다음 핸들러로 처리를 계속 이어나갈 수 있습니다.
                .handle(Sftp.outboundGateway(sftpSessionFactory, "put", "payload")
                        .useTemporaryFileName(true) // 업로드 도중 임시 파일명 사용 활성화
                        .temporaryFileSuffix(tempFileSuffix)
                        .remoteDirectoryExpression("'" + remoteUploadDir + "'")
                )
                // 3. SFTP 전송 성공 후 최종 DB 상태 업데이트 및 로컬 임시 파일 정리
                .handle(this::updateStatusToProcessed)
                .get();
    }

    /**
     * SFTP 업로드 성공 후 호출되는 최종 DB 상태 업데이트 핸들러
     * - SFTP 연동은 네트워크 I/O이므로 최초 DB 조회 트랜잭션 외부에서 비동기 격리 처리하는 것이 권장됩니다.
     * - 업로드 성공 시점에 헤더에서 ID 목록을 꺼내 'PROCESSED' 상태로 일괄 업데이트(Commit)합니다.
     */
    @SuppressWarnings("unchecked")
    private void updateStatusToProcessed(Message<?> message) {
        // SftpOutboundGateway PUT 커맨드의 결과 Payload는 업로드 완료된 원격지 파일 경로(String)입니다.
        String remotePath = (String) message.getPayload();
        log.info("[DB Export] SFTP 업로드 완료 (원격 경로: {})", remotePath);

        List<Integer> ids = (List<Integer>) message.getHeaders().get("export_ids");
        String localFilePath = (String) message.getHeaders().get("local_file_path");

        if (ids != null && !ids.isEmpty()) {
            NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
            MapSqlParameterSource parameters = new MapSqlParameterSource();
            parameters.addValue("ids", ids);

            // 상태를 최종 처리 완료(PROCESSED)로 업데이트
            int updated = jdbcTemplate.update(
                    "UPDATE user_export SET status = 'PROCESSED', updated_at = NOW() WHERE id IN (:ids)", 
                    parameters
            );
            log.info("[DB Export] DB 최종 갱신 완료. 테이블 상태 업데이트 건수: {}건", updated);
        }

        // 전송 완료된 로컬 임시 CSV 파일 삭제
        if (localFilePath != null) {
            File file = new File(localFilePath);
            if (file.exists()) {
                boolean deleted = file.delete();
                log.debug("[DB Export] 로컬 임시 CSV 파일 삭제 여부: {} ({})", deleted, file.getName());
            }
        }
    }


    /*
     * =========================================================================
     * [요구사항 6] SFTP ➔ DB Import 플로우
     * =========================================================================
     */

    /**
     * DB Import용 원격 SFTP 서버 감시 동기화 장치 (Inbound Synchronizer)
     * - 요구사항 3의 방어 로직을 그대로 승계합니다. (임시 확장자 배제 및 중복 다운로드 차단)
     */
    @Bean
    public SftpInboundFileSynchronizer sftpImportSynchronizer() {
        SftpInboundFileSynchronizer synchronizer = new SftpInboundFileSynchronizer(sftpSessionFactory);
        synchronizer.setRemoteDirectory(remoteWatchDir);
        
        // 방어 1: 완성된 CSV 확장자만 매칭하여 다운로드 (송신 측의 *.writing 임시 파일은 스킵)
        SftpSimplePatternFileListFilter patternFilter = new SftpSimplePatternFileListFilter("*.csv");
        
        // 방어 2: 이미 처리 완료된 파일은 중복 다운로드 차단
        SftpPersistentAcceptOnceFileListFilter acceptOnceFilter =
                new SftpPersistentAcceptOnceFileListFilter(new SimpleMetadataStore(), "sftpImportFilter");

        ChainFileListFilter<SftpClient.DirEntry> chainFilter = new ChainFileListFilter<>();
        chainFilter.addFilter(acceptOnceFilter);
        chainFilter.addFilter(patternFilter);
        
        synchronizer.setFilter(chainFilter);
        
        // 다운로드 완료 즉시 원격 원본 파일 자동 삭제
        synchronizer.setDeleteRemoteFiles(true);
        return synchronizer;
    }

    /**
     * 다운로드 받은 로컬 폴더를 스캔하여 가공 메시지를 만드는 MessageSource
     */
    @Bean
    public SftpInboundFileSynchronizingMessageSource sftpImportMessageSource(
            SftpInboundFileSynchronizer sftpImportSynchronizer) {
        
        File directory = new File(localDownloadDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        SftpInboundFileSynchronizingMessageSource source =
                new SftpInboundFileSynchronizingMessageSource(sftpImportSynchronizer);
        source.setLocalDirectory(directory);
        source.setLocalFilter(new AcceptOnceFileListFilter<>()); // 로컬 중복 방지
        return source;
    }

    /**
     * PostgreSQL DB Import용 JDBC Outbound Message Handler
     * - NamedParameterJdbcTemplate 기반으로 DTO의 필드명을 SQL 파라미터에 매핑합니다.
     * - UPSERT 처리: PostgreSQL의 'ON CONFLICT (id) DO UPDATE' 문법을 사용하여 중복 키 입력 시 데이터가 Update 되도록 구성합니다.
     */
    @Bean
    public MessageHandler jdbcImportMessageHandler() {
        // UPSERT SQL문 구성
        String upsertSql = "INSERT INTO user_import (id, username, email) " +
                           "VALUES (:id, :username, :email) " +
                           "ON CONFLICT (id) DO UPDATE SET " +
                           "username = EXCLUDED.username, " +
                           "email = EXCLUDED.email, " +
                           "created_at = NOW()";

        JdbcMessageHandler handler = new JdbcMessageHandler(dataSource, upsertSql);
        // Payload 객체(UserImportDto)의 프로퍼티를 SQL 파라미터에 바인딩하도록 설정
        handler.setSqlParameterSourceFactory(message -> {
            // Spring Integration은 message 객체 자체를 파라미터 팩토리의 input으로 전달합니다.
            org.springframework.messaging.Message<?> msg = (org.springframework.messaging.Message<?>) message;
            UserImportDto dto = (UserImportDto) msg.getPayload();
            return new MapSqlParameterSource()
                    .addValue("id", dto.getId())
                    .addValue("username", dto.getUsername())
                    .addValue("email", dto.getEmail());
        });
        return handler;
    }

    /**
     * SFTP 파일 다운로드 ➔ 줄(Line) 파싱 ➔ DTO 변환 ➔ PostgreSQL UPSERT 처리 통합 Flow
     */
    @Bean
    public IntegrationFlow sftpToDbImportFlow(
            SftpInboundFileSynchronizingMessageSource sftpImportMessageSource,
            MessageHandler jdbcImportMessageHandler) {
        
        return IntegrationFlow.from(sftpImportMessageSource,
                        c -> c.poller(Pollers.fixedDelay(Duration.ofMillis(dbPollingRateMs))))
                // 1. 다운로드 완료된 파일 객체(File)를 줄(Line) 단위로 쪼개어 개별 String 메시지로 생성 (FileSplitter)
                .split(new FileSplitter())
                // 2. CSV 헤더 및 빈 라인을 DTO 변환 전에 미리 필터링하여 스킵 (ReplyRequiredException 예방)
                .filter((String line) -> line != null && !line.trim().isEmpty() && !line.startsWith("id,username,email"))
                // 3. CSV 라인 한 줄(String)을 UserImportDto 객체로 파싱
                .transform(csvTransformer, "parseCsvLineToDto")
                // 4. 파싱된 DTO를 PostgreSQL DB에 배치성으로 Insert/UPSERT 실행
                .handle(jdbcImportMessageHandler)
                .get();
    }
}
