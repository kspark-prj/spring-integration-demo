package com.example.integration.runner;

import com.example.integration.config.SftpIntegrationConfig.SftpUploadGateway;
import com.example.integration.config.TcpIntegrationConfig.TcpClientGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spring Integration 데모를 실행 및 시뮬레이션하는 CommandLineRunner
 */
@Component
public class IntegrationDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IntegrationDemoRunner.class);

    @Value("${demo.local.watch-dir}")
    private String localWatchDir;

    @Value("${demo.sftp.local-download-dir}")
    private String sftpLocalDownloadDir;

    @Autowired(required = false)
    private SftpUploadGateway sftpUploadGateway;

    @Autowired(required = false)
    private TcpClientGateway tcpClientGateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) throws Exception {
        log.info("========================================================================");
        log.info("Spring Integration 데모 시뮬레이션을 시작합니다. (DB / 파일 / TCP / SFTP)");
        log.info("========================================================================");

        // 1. [로컬 파일 감시 테스트] 임시 파일 생성
        simulateLocalFileCreation();

        // 2. [TCP 통신 테스트] 클라이언트 게이트웨이 호출 시뮬레이션
        simulateTcpCommunication();

        // 3. [SFTP 파일 업로드 테스트 안내]
        simulateSftpUpload();

        // 4. [DB 연동 테스트] PostgreSQL DB 상태 및 SFTP Import 시뮬레이션
        simulateDatabaseIntegration();
    }

    /**
     * 로컬 파일 감시를 시뮬레이션하기 위해 감시 디렉터리에 파일을 생성합니다.
     */
    private void simulateLocalFileCreation() {
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                
                File dir = new File(localWatchDir);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                
                File testFile = new File(dir, "demo-test-file.txt");
                log.info("[Local Simulator] 파일 쓰기 시작 (Incomplete File Watcher 방어 테스트용)...: {}", testFile.getName());
                
                try (FileWriter writer = new FileWriter(testFile)) {
                    writer.write("Hello, Spring Integration!\n");
                    writer.flush();
                    
                    log.info("[Local Simulator] 1차 쓰기 완료. 3초간 잠금(대기)하여 미완성 파일 상태 유지...");
                    Thread.sleep(3000);
                    
                    writer.write("This is a complete file content written safely.\n");
                    writer.write("Spring Boot 3 + Spring Integration 6.x is awesome.");
                    writer.flush();
                }
                
                log.info("[Local Simulator] 파일 쓰기 완료. LastModifiedFileListFilter 임계치 도달 후 감지됩니다.");
                
            } catch (IOException | InterruptedException e) {
                log.error("[Local Simulator] 파일 쓰기 테스트 중 오류 발생", e);
            }
        }).start();
    }

    /**
     * TCP Client Gateway를 사용하여 자기 자신의 TCP Server로 메시지를 전송하고 응답을 받습니다.
     */
    private void simulateTcpCommunication() {
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                
                if (tcpClientGateway != null) {
                    log.info("[TCP Client Simulator] TCP 서버로 'PING' 메시지 전송 시도...");
                    String pingResponse = tcpClientGateway.sendAndReceive("PING");
                    log.info("[TCP Client Simulator] 'PING' 응답 결과: '{}'", pingResponse);

                    log.info("[TCP Client Simulator] TCP 서버로 'Hello Server' 메시지 전송 시도...");
                    String helloResponse = tcpClientGateway.sendAndReceive("Hello Server");
                    log.info("[TCP Client Simulator] 'Hello Server' 응답 결과: '{}'", helloResponse);
                }
            } catch (Exception e) {
                log.warn("[TCP Client Simulator] TCP 서버와의 통신에 실패했습니다. (서버 연결 대기 또는 소켓 오류: {})", e.getMessage());
            }
        }).start();
    }

    /**
     * SFTP 파일 전송 게이트웨이 호출 시뮬레이션
     */
    private void simulateSftpUpload() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                if (sftpUploadGateway != null) {
                    File tempUploadFile = File.createTempFile("sftp-temp-upload-", ".txt");
                    try (FileWriter writer = new FileWriter(tempUploadFile)) {
                        writer.write("SFTP Safe Upload Integration Test Content");
                    }
                    
                    log.info("[SFTP Simulator] SFTP 업로드 게이트웨이를 통해 파일 전송을 시도합니다. (임시파일: {})", tempUploadFile.getName());
                    try {
                        sftpUploadGateway.uploadFile(tempUploadFile);
                        log.info("[SFTP Simulator] SFTP 파일 업로드 요청 송신 성공!");
                    } catch (Exception e) {
                        log.warn("[SFTP Simulator] SFTP 업로드에 실패했습니다. (SFTP 서버 미구동 상태일 수 있음): {}", e.getMessage());
                    } finally {
                        tempUploadFile.deleteOnExit();
                    }
                }
            } catch (Exception e) {
                log.error("[SFTP Simulator] SFTP 테스트 실행 중 에러", e);
            }
        }).start();
    }

    /**
     * PostgreSQL DB 연동 및 SFTP Import 시뮬레이션
     * - DB Export 현황 로그 확인.
     * - 실제 SFTP 서버가 없어도 로컬 다운로드 경로에 임의의 CSV 파일을 써넣어,
     *   "다운로드 이후 파일 파싱 및 DB Batch Import (UPSERT) 흐름"이 완벽하게 가동되는지 로컬 시뮬레이션합니다.
     */
    private void simulateDatabaseIntegration() {
        new Thread(() -> {
            try {
                Thread.sleep(6000);
                log.info("[DB Simulator] 데이터베이스 적재 현황 및 Import 시뮬레이션을 수행합니다.");

                // 1. Export 대상 DB 데이터 조회 (초기 데이터)
                try {
                    List<Map<String, Object>> pendingList = jdbcTemplate.queryForList(
                            "SELECT * FROM user_export"
                    );
                    log.info("[DB Simulator] [USER_EXPORT] 테이블 적재 데이터 개수: {}건", pendingList.size());
                    for (Map<String, Object> row : pendingList) {
                        log.info("[DB Simulator] [USER_EXPORT] ID: {}, USERNAME: {}, STATUS: {}", 
                                row.get("id"), row.get("username"), row.get("status"));
                    }
                } catch (Exception e) {
                    log.warn("[DB Simulator] 데이터베이스(PostgreSQL) 조회가 차단되었습니다. (DB 구동 또는 스키마 생성 여부를 확인해 주세요): {}", e.getMessage());
                }

                // 2. SFTP ➔ DB Import Flow를 동작시키기 위한 로컬 가상 CSV 파일 생성
                // (이 파일은 sftpToDbImportFlow 의 로컬 폴링 디렉터리로 감지되어 자동으로 파싱 및 DB UPSERT 처리됩니다.)
                File sftpDownloadDirFile = new File(sftpLocalDownloadDir);
                if (!sftpDownloadDirFile.exists()) {
                    sftpDownloadDirFile.mkdirs();
                }

                File mockDownloadedCsv = new File(sftpDownloadDirFile, "mock_downloaded_users_" + UUID.randomUUID() + ".csv");
                log.info("[DB Simulator] 로컬 다운로드 경로에 가상 SFTP 다운로드 완료 CSV 생성: {}", mockDownloadedCsv.getName());

                try (FileWriter writer = new FileWriter(mockDownloadedCsv)) {
                    // CSV 헤더 작성
                    writer.write("id,username,email\n");
                    // CSV 데이터 작성 (Import할 3명의 유저 정보)
                    writer.write("uuid-1111-2222,sejong_king,sejong@joseon.gov\n");
                    writer.write("uuid-3333-4444,admiral_yi,sunsin@joseon.gov\n");
                    writer.write("uuid-5555-6666,gildong_hero,hong@yuldoguk.org\n");
                    writer.flush();
                }

                log.info("[DB Simulator] 가상 CSV 파일 작성이 완료되었습니다. Import Flow에 의해 즉시 파싱 및 DB (user_import) 저장됩니다.");

                // 5초간 대기 후 user_import 테이블 조회
                Thread.sleep(5000);
                try {
                    List<Map<String, Object>> importedList = jdbcTemplate.queryForList(
                            "SELECT * FROM user_import"
                    );
                    log.info("[DB Simulator] [USER_IMPORT] DB 적재(Import) 결과 (총 {}건):", importedList.size());
                    for (Map<String, Object> row : importedList) {
                        log.info("[DB Simulator] [USER_IMPORT] ID: {}, USERNAME: {}, EMAIL: {}", 
                                row.get("id"), row.get("username"), row.get("email"));
                    }
                } catch (Exception e) {
                    log.warn("[DB Simulator] [USER_IMPORT] 결과 조회 실패 (DB 미구동 또는 쿼리 에러): {}", e.getMessage());
                }

            } catch (IOException | InterruptedException e) {
                log.error("[DB Simulator] 데이터베이스 시뮬레이션 중 오류", e);
            }
        }).start();
    }
}
