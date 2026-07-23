package com.example.integration.config;

import com.example.integration.service.FileProcessService;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.filters.AcceptOnceFileListFilter;
import org.springframework.integration.file.filters.ChainFileListFilter;
import org.springframework.integration.file.remote.session.CachingSessionFactory;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.metadata.SimpleMetadataStore;
import org.springframework.integration.sftp.dsl.Sftp;
import org.springframework.integration.sftp.filters.SftpPersistentAcceptOnceFileListFilter;
import org.springframework.integration.sftp.filters.SftpSimplePatternFileListFilter;
import org.springframework.integration.sftp.inbound.SftpInboundFileSynchronizer;
import org.springframework.integration.sftp.inbound.SftpInboundFileSynchronizingMessageSource;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import java.io.File;
import java.time.Duration;

/**
 * [요구사항 2, 3] SFTP File Transfer & Safe Directory Watcher 설정
 * - DefaultSftpSessionFactory 및 SftpRemoteFileTemplate 빈 설정.
 * - SFTP Outbound Adapter 설정 (안전한 업로드).
 * - SFTP Inbound Adapter 설정 (다운로드 중 깨짐 방지 및 안전한 폴링).
 */
@Configuration
public class SftpIntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(SftpIntegrationConfig.class);

    @Value("${demo.sftp.host}")
    private String host;

    @Value("${demo.sftp.port}")
    private int port;

    @Value("${demo.sftp.username}")
    private String username;

    @Value("${demo.sftp.password}")
    private String password;

    @Value("${demo.sftp.local-download-dir}")
    private String localDownloadDir;

    @Value("${demo.sftp.remote-watch-dir}")
    private String remoteWatchDir;

    @Value("${demo.sftp.remote-upload-dir}")
    private String remoteUploadDir;

    @Value("${demo.sftp.temp-file-suffix}")
    private String tempFileSuffix;

    @Value("${demo.sftp.polling-rate-ms}")
    private long pollingRateMs;

    /**
     * SFTP 세션을 관리하는 SessionFactory 빈 등록
     * - Apache SSHD 기반의 DefaultSftpSessionFactory 사용 (Spring Boot 3 / Integration 6.x 표준)
     * - 세션 생성 비용을 줄이기 위해 CachingSessionFactory로 래핑
     */
    @Bean
    public SessionFactory<SftpClient.DirEntry> sftpSessionFactory() {
        DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory(true);
        factory.setHost(host);
        factory.setPort(port);
        factory.setUser(username);
        factory.setPassword(password);
        factory.setAllowUnknownKeys(true); // 개발/테스트 환경에서 검증되지 않은 SFTP 서버 호스트 키 허용
        // 프로덕션 환경에서는 password 대신 setPrivateKey(Resource)로 Key 기반 인증 권장
        
        // CachingSessionFactory를 통해 세션을 풀(Pool)로 재사용
        return new CachingSessionFactory<>(factory, 10);
    }

    /**
     * SFTP 관련 작업을 프로그램 방식으로 정밀 제어하기 위한 Template 빈 등록
     */
    @Bean
    public SftpRemoteFileTemplate sftpRemoteFileTemplate(SessionFactory<SftpClient.DirEntry> sftpSessionFactory) {
        SftpRemoteFileTemplate template = new SftpRemoteFileTemplate(sftpSessionFactory);
        template.setRemoteDirectoryExpression(new LiteralExpression(remoteUploadDir));
        return template;
    }

    /*
     * =========================================================================
     * [요구사항 2] SFTP Outbound File Transfer (업로드)
     * =========================================================================
     */

    /**
     * SFTP 업로드 요청 메시지를 전달받을 채널
     */
    @Bean
    public MessageChannel sftpUploadChannel() {
        return new DirectChannel();
    }

    /**
     * Gateway Interface
     * 애플리케이션 서비스 레이어에서 이 인터페이스를 주입받아 uploadFile(file) 메서드를 호출하면,
     * 내부적으로 sftpUploadChannel로 파일 메시지가 발행되어 SFTP 전송이 실행됩니다.
     */
    @MessagingGateway(defaultRequestChannel = "sftpUploadChannel")
    public interface SftpUploadGateway {
        void uploadFile(File file);
    }

    /**
     * SFTP Outbound Channel Adapter Flow
     * - sftpUploadChannel로 유입된 파일을 원격 SFTP 서버로 업로드합니다.
     * - 핵심 방어: temporaryFileSuffix(".writing")를 설정하여,
     *   업로드 중에는 파일명에 임시 확장자를 붙이고 업로드가 완전히 완료되면 원래 파일명으로 Rename합니다.
     *   이를 통해 다운로드받는 측(수신자)에서 불완전하게 쓰여진 파일을 읽어가는 것을 완벽하게 방지합니다.
     */
    @Bean
    public IntegrationFlow sftpUploadFlow(SessionFactory<SftpClient.DirEntry> sftpSessionFactory) {
        return IntegrationFlow.from(sftpUploadChannel())
                .handle(Sftp.outboundAdapter(sftpSessionFactory)
                        .remoteDirectory(remoteUploadDir)
                        .temporaryFileSuffix(tempFileSuffix) // 업로드 도중: file.txt.writing -> 완료 후: file.txt 로 자동 Rename
                        .autoCreateDirectory(true)
                )
                .get();
    }

    /*
     * =========================================================================
     * [요구사항 3] SFTP Inbound Watcher & Safe Download (다운로드)
     * =========================================================================
     */

    /**
     * SFTP 동기화 설정 (Inbound File Synchronizer)
     * - 원격 서버의 파일을 로컬 디렉터리로 안전하게 가져오는 역할을 담당합니다.
     * - 핵심 방어: 원격지에 아직 작성 중인 파일(예: *.writing)을 다운로드 대상에서 제외하는 필터를 구성합니다.
     */
    @Bean
    public SftpInboundFileSynchronizer sftpInboundFileSynchronizer(
            SessionFactory<SftpClient.DirEntry> sftpSessionFactory) {
        
        SftpInboundFileSynchronizer synchronizer = new SftpInboundFileSynchronizer(sftpSessionFactory);
        synchronizer.setRemoteDirectory(remoteWatchDir);

        /*
         * 방어 1: SftpSimplePatternFileListFilter
         * 원격 파일이 완성된 파일 확장자(예: .txt, .json 등)를 가질 때만 다운로드하도록 걸러냅니다.
         * 송신자가 업로드 시 .writing 임시 확장자를 사용하는 방식을 연동하여,
         * 임시 파일(.writing)은 다운로드 대상에서 걸러냅니다. (이것이 가장 안정적인 안전 다운로드 패턴입니다)
         * 여기서는 확장자 패턴을 지정하지 않고 *.writing 을 배제하거나, 특정 완성 패턴(예: *.txt)만 통과시키도록 설정할 수 있습니다.
         */
        SftpSimplePatternFileListFilter patternFilter = new SftpSimplePatternFileListFilter("*.txt");

        /*
         * 방어 2: SftpPersistentAcceptOnceFileListFilter
         * 이미 다운로드 처리 완료된 원격 파일들의 메타데이터(이름, 크기, 수정시간)를 기억하여 중복 처리를 차단합니다.
         */
        SftpPersistentAcceptOnceFileListFilter acceptOnceFilter =
                new SftpPersistentAcceptOnceFileListFilter(new SimpleMetadataStore(), "sftpInboundFilter");

        // 두 필터를 체인으로 연결하여 synchronizer에 주입
        ChainFileListFilter<SftpClient.DirEntry> chainFilter = new ChainFileListFilter<>();
        chainFilter.addFilter(acceptOnceFilter);
        chainFilter.addFilter(patternFilter);
        
        synchronizer.setFilter(chainFilter);

        /*
         * 원격 파일 처리 완료 후의 정책 설정 안내:
         * 1) 원격지 원본 자동 삭제: setDeleteRemoteFiles(true) 설정 시 로컬로의 다운로드가 완료되는 즉시 원격지의 원본 파일이 자동 삭제됩니다.
         * 2) 원격지 이관(Move/Rename): 다운로드 완료 후 원격지 백업 폴더로 이동하려면, 다운로드 처리 Flow 이후
         *    SftpOutboundGateway를 통해 'mv' (rename) 명령어를 원격 서버에 전달하는 설정을 추가할 수 있습니다. (아래 Flow 예시 주석 참고)
         */
        synchronizer.setDeleteRemoteFiles(false); // 여기서는 일단 false로 둡니다 (로그 완료 처리 후 이관하거나 보관하기 위함)

        return synchronizer;
    }

    /**
     * 로컬로 복사된 파일 메시지를 발행하는 MessageSource
     */
    @Bean
    public SftpInboundFileSynchronizingMessageSource sftpInboundMessageSource(
            SftpInboundFileSynchronizer sftpInboundFileSynchronizer) {
        
        // 로컬 다운로드 저장 경로가 없는 경우 폴더 생성
        File directory = new File(localDownloadDir);
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            log.info("SFTP 로컬 다운로드 디렉터리 생성 여부: {} ({})", created, localDownloadDir);
        }

        SftpInboundFileSynchronizingMessageSource source =
                new SftpInboundFileSynchronizingMessageSource(sftpInboundFileSynchronizer);
        source.setLocalDirectory(directory);
        
        // 로컬로 가져온 이후, 로컬 파일 중복 처리를 예방하기 위해 AcceptOnceFileListFilter를 장착
        source.setLocalFilter(new AcceptOnceFileListFilter<>());
        
        return source;
    }

    /**
     * SFTP Directory Watcher & Download Flow
     * - 주기적으로 SFTP 원격지를 스캔하여 로컬로 안전하게 다운로드합니다.
     * - 다운로드가 성공한 로컬 파일 메시지를 수신하여 비즈니스 서비스(FileProcessService)로 보냅니다.
     */
    @Bean
    public IntegrationFlow sftpInboundFlow(
            SftpInboundFileSynchronizingMessageSource sftpInboundMessageSource,
            FileProcessService fileProcessService) {
        
        return IntegrationFlow.from(sftpInboundMessageSource,
                        c -> c.poller(Pollers.fixedDelay(Duration.ofMillis(pollingRateMs))))
                // Payload는 다운로드 완료된 로컬 파일(java.io.File)입니다.
                .handle(fileProcessService, "processDownloadedFile")
                // 만약 다운로드 및 로컬 처리 완료 후 원격지 파일을 다른 디렉터리로 '이관(Move)' 하고 싶다면:
                // 아래처럼 SftpOutboundGateway를 연계하여 'mv' 명령을 수행할 수 있습니다.
                // .handle(Sftp.outboundGateway(sftpSessionFactory(), Command.MV, "headers['file_remoteDirectory'] + '/' + headers['file_remoteFile']")
                //         .renameExpression("headers['file_remoteDirectory'] + '/backup/' + headers['file_remoteFile']"))
                .get();
    }
}
