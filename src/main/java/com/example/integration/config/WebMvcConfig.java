package com.example.integration.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 루트 경로 (/) 접속 시 /dashboard.html로 자동 리다이렉트 처리
        registry.addRedirectViewController("/", "/dashboard.html");
    }
}
