package com.example.integration.config;

import java.time.LocalDateTime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.InboundChannelAdapter;
import org.springframework.integration.annotation.Poller;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.MessageChannel;

@Configuration
public class CronIntegrationConfig {

    // 1. 메시지가 흐를 채널 정의
    @Bean
    public MessageChannel cronChannel() {
        return new DirectChannel();
    }

    // 2. Cron 주기마다 실행되어 메시지를 생성하는 Inbound Adapter
    // 매분 0초마다 실행 (5초마다 하고 싶다면 "*/5 * * * * *" 사용)
    @InboundChannelAdapter(value = "cronChannel", poller = @Poller(cron = "0 * * * * *"))
    public String generateCronMessage() {
        String msg = "Cron Triggered at: " + LocalDateTime.now();
        System.out.println("[Producer] " + msg);
        return msg;
    }

    // 3. 채널 수신 후 실제 비즈니스 로직 처리 (Consumer)
    @ServiceActivator(inputChannel = "cronChannel")
    public void handleCronMessage(String payload) {
        System.out.println("[Consumer] Received Payload: " + payload);
    }
}