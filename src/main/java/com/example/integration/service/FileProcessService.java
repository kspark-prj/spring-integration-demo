package com.example.integration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 로컬 파일 감시 및 SFTP 다운로드 파일 처리를 담당하는 비즈니스 서비스
 */
@Service
public class FileProcessService {

    private static final Logger log = LoggerFactory.getLogger(FileProcessService.class);

    /**
     * [요구사항 1] 로컬 폴더 감시에서 감지된 완전히 생성된 파일 처리
     * - 파일의 내용을 읽어 로그로 출력합니다.
     */
    public void processLocalFile(File file) {
        log.info("[Local Watcher] 신규 완성 파일 감지: {}", file.getAbsolutePath());
        
        try {
            // 파일 크기 로그 출력
            log.info("[Local Watcher] 파일 크기: {} bytes", file.length());
            
            // 파일 내용 읽기 (텍스트 파일 기준)
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            log.info("[Local Watcher] --- 파일 내용 시작 ---");
            log.info("\n{}", content);
            log.info("[Local Watcher] --- 파일 내용 끝 ---");
            
        } catch (IOException e) {
            log.error("[Local Watcher] 파일 내용을 읽는 도중 오류가 발생했습니다. 파일: {}", file.getName(), e);
        }
    }

    /**
     * [요구사항 3] SFTP에서 로컬로 안전하게 다운로드 완료된 파일 처리
     * - 다운로드 완료 로그를 출력합니다.
     */
    public void processDownloadedFile(File file) {
        log.info("[SFTP Watcher] 로컬 다운로드 및 안전 전송 완료: {}", file.getAbsolutePath());
        log.info("[SFTP Watcher] 파일명: {}, 크기: {} bytes", file.getName(), file.length());
        
        // 다운로드 성공 후 후속 비즈니스 처리 위치
        // 예: DB 적재, 외부 API 연동 등
        log.info("[SFTP Watcher] 비즈니스 연동 완료. (로컬 다운로드 파일 안전 보관 상태)");
    }
}
