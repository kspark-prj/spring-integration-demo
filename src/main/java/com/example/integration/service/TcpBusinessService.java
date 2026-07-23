package com.example.integration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * [요구사항 4] TCP 서버 수신 메시지 비즈니스 로직 처리 서비스
 */
@Service
public class TcpBusinessService {

    private static final Logger log = LoggerFactory.getLogger(TcpBusinessService.class);

    /**
     * 클라이언트로부터 전달받은 TCP 문자열 요청 처리
     * @param requestPayload 디코딩된 요청 메시지 (String)
     * @return 클라이언트에 전송할 응답 메시지 (String)
     */
    public String handleRequest(String requestPayload) {
        log.info("[TCP Server] 요청 수신: '{}'", requestPayload);

        // 간단한 비즈니스 로직 예시:
        // 요청 데이터가 비었거나 특정 조건에 따른 응답 분기
        String response;
        if (requestPayload == null || requestPayload.trim().isEmpty()) {
            response = "ERROR: Empty Payload";
        } else if (requestPayload.startsWith("PING")) {
            response = "PONG";
        } else {
            // 시간 정보를 붙인 에코 응답 반환
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            response = String.format("ACK: Received [%s] at %s", requestPayload, time);
        }

        log.info("[TCP Server] 응답 전송 예정: '{}'", response);
        return response;
    }
}
