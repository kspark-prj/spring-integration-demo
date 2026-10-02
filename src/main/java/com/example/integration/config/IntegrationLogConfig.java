package com.example.integration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.config.GlobalChannelInterceptor;

import com.example.integration.interceptor.MdcLogInterceptor;

@Configuration
@EnableIntegration
public class IntegrationLogConfig {

    @Bean
    @GlobalChannelInterceptor(patterns = "*") // 모든 채널에 공통 적용
    public MdcLogInterceptor mdcLogInterceptor() {
        return new MdcLogInterceptor();
    }
}
