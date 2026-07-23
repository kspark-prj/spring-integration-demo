package com.example.integration.config;

import com.example.integration.service.TcpBusinessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.ip.dsl.Tcp;
import org.springframework.integration.ip.tcp.connection.AbstractClientConnectionFactory;
import org.springframework.integration.ip.tcp.connection.AbstractServerConnectionFactory;
import org.springframework.integration.ip.tcp.connection.TcpNetClientConnectionFactory;
import org.springframework.integration.ip.tcp.connection.TcpNetServerConnectionFactory;
import org.springframework.integration.ip.tcp.serializer.ByteArrayCrLfSerializer;
import org.springframework.messaging.MessageChannel;

/**
 * [요구사항 4] TCP/IP Communication 설정
 * - TCP Server Connection Factory 및 TCP Client Connection Factory 설정.
 * - ByteArrayCrLfSerializer/Deserializer 기반 메시지 프레이밍.
 * - Inbound Gateway를 통해 요청 수신 후 비즈니스 로직 수행 및 응답 반환 처리.
 * - Outbound Gateway를 통해 외부 TCP 서버로 요청하고 응답받는 흐름 구성.
 */
@Configuration
public class TcpIntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(TcpIntegrationConfig.class);

    @Value("${demo.tcp.server.port}")
    private int serverPort;

    @Value("${demo.tcp.client.host}")
    private String clientHost;

    @Value("${demo.tcp.client.port}")
    private int clientPort;

    @Value("${demo.tcp.client.timeout-ms}")
    private int clientTimeoutMs;

    /*
     * =========================================================================
     * 1. Connection Factories & Serialization
     * =========================================================================
     */

    /**
     * TCP 서버 커넥션 팩토리
     * - 클라이언트의 연결을 대기하는 서버 소켓 생성 관리
     */
    @Bean
    public AbstractServerConnectionFactory serverConnectionFactory() {
        TcpNetServerConnectionFactory factory = new TcpNetServerConnectionFactory(serverPort);
        
        // ByteArrayCrLfSerializer: 데이터의 끝을 CRLF(\r\n, 0x0d 0x0a)로 인식하고 구분하여 프레임을 맞춥니다.
        // 송신 시 자동으로 끝에 CRLF를 붙이고, 수신 시 CRLF를 기준으로 데이터를 분할하여 byte[]로 변환합니다.
        factory.setSerializer(ByteArrayCrLfSerializer.INSTANCE);
        factory.setDeserializer(ByteArrayCrLfSerializer.INSTANCE);
        
        // 단일 커넥션에서 다중 요청/응답을 순차적으로 처리하도록 설정 (기본값 true)
        factory.setSingleUse(false);
        log.info("TCP Server Connection Factory initialized on port: {}", serverPort);
        return factory;
    }

    /**
     * TCP 클라이언트 커넥션 팩토리
     * - 외부 TCP 서버에 접속하기 위한 소켓 생성 관리
     */
    @Bean
    public AbstractClientConnectionFactory clientConnectionFactory() {
        TcpNetClientConnectionFactory factory = new TcpNetClientConnectionFactory(clientHost, clientPort);
        factory.setSerializer(ByteArrayCrLfSerializer.INSTANCE);
        factory.setDeserializer(ByteArrayCrLfSerializer.INSTANCE);
        factory.setSingleUse(false);
        // 소켓 타임아웃 설정
        factory.setSoTimeout(clientTimeoutMs);
        log.info("TCP Client Connection Factory initialized for {}:{}", clientHost, clientPort);
        return factory;
    }

    /*
     * =========================================================================
     * 2. TCP Server Flow (요청 수신 -> 비즈니스 처리 -> 응답 반환)
     * =========================================================================
     */

    /**
     * TCP Inbound Gateway Flow
     * - serverConnectionFactory로부터 연결 요청 및 데이터(byte[])를 받습니다.
     * - 수신된 byte[] 데이터를 String 문자열로 변환합니다.
     * - TcpBusinessService로 라우팅하여 비즈니스 로직을 수행하고 결과 문자열을 반환합니다.
     * - 반환된 결과는 자동으로 Serializer를 통해 CRLF가 붙은 byte[] 형식으로 클라이언트에 전송됩니다.
     */
    @Bean
    public IntegrationFlow tcpServerFlow(
            AbstractServerConnectionFactory serverConnectionFactory,
            TcpBusinessService tcpBusinessService) {
        
        return IntegrationFlow.from(Tcp.inboundGateway(serverConnectionFactory))
                // 수신된 byte[] 페이로드를 String으로 디코딩 및 변환
                .transform(Transformers.objectToString("UTF-8"))
                // 비즈니스 로직을 수행하는 서비스 빈과 메서드 매핑
                .handle(tcpBusinessService, "handleRequest")
                .get();
    }

    /*
     * =========================================================================
     * 3. TCP Client Flow (요청 송신 -> 응답 대기 -> 결과 반환)
     * =========================================================================
     */

    /**
     * 클라이언트 요청을 보낼 메시지 채널
     */
    @Bean
    public MessageChannel tcpClientRequestChannel() {
        return new DirectChannel();
    }

    /**
     * TCP Client Gateway Interface
     * - 다른 서비스 빈에서 이 인터페이스를 호출하여 외부 TCP 서버에 요청을 전송하고 동기적으로 응답을 받을 수 있습니다.
     */
    @MessagingGateway(defaultRequestChannel = "tcpClientRequestChannel")
    public interface TcpClientGateway {
        String sendAndReceive(String message);
    }

    /**
     * TCP Client Outbound Gateway Flow
     * - tcpClientRequestChannel로 전달된 String 요청 메시지를 수신합니다.
     * - Tcp.outboundGateway를 통해 외부 TCP 서버로 전송하고 응답을 대기합니다.
     * - 수신된 byte[] 응답 페이로드를 String으로 인코딩하여 게이트웨이 호출자에게 반환합니다.
     */
    @Bean
    public IntegrationFlow tcpClientFlow(AbstractClientConnectionFactory clientConnectionFactory) {
        return IntegrationFlow.from(tcpClientRequestChannel())
                // 외부 TCP 서버로 요청 전송 후 응답 대기 (Request-Reply 패턴)
                .handle(Tcp.outboundGateway(clientConnectionFactory))
                // 응답 byte[] 페이로드를 String으로 변환
                .transform(Transformers.objectToString("UTF-8"))
                .get();
    }
}
