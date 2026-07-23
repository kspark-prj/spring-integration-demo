package com.example.integration.config;

import java.util.List;
import java.util.concurrent.Executors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.http.dsl.Http;
import org.springframework.web.client.DefaultResponseErrorHandler;

@Configuration
public class CrawlerConfig {

    private static final List<String> TARGET_URLS = List.of(
            "https://news.ycombinator.com",
            "https://news.v.daum.net",
            "https://invalid-url-example-404.com"
    );

    // =========================================================================
    // 예제 1. 개별 처리 Flow: 각 URL의 크롤링이 끝나는 "즉시" 개별 스레드에서 로그 출력
    // =========================================================================
    @Bean
    public IntegrationFlow individualResultCrawlerFlow() {
        return IntegrationFlow
                .fromSupplier(() -> TARGET_URLS,
                        e -> e.poller(Pollers.cron("0 * * * * *")))

                .split()
                .channel(c -> c.executor(Executors.newVirtualThreadPerTaskExecutor()))

                // 🌟 [수정] HTTP 요청 전 헤더를 안전하게 추가 (.enrichHeaders)
                .enrichHeaders(h -> h.header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))

                // HTTP GET 요청
                .handle(Http.outboundGateway(url -> url.getPayload())
                        .httpMethod(HttpMethod.GET)
                        .expectedResponseType(String.class)
                        .errorHandler(new SafeResponseErrorHandler()))

                // Jsoup 파싱 및 결과 변환
                .<Object, String>transform(payload -> parseTitle(payload))

                // 각 스레드 완료 즉시 호출
                .handle(String.class, (result, headers) -> {
                    System.out.println("[개별 흐름 - " + Thread.currentThread().getName() + "] " + result);
                    return null;
                })
                .get();
    }

    // =========================================================================
    // 예제 2. 집계 처리 Flow: 병렬 수집 후 모든 URL 결과가 "다 모였을 때" 한 번에 출력
    // =========================================================================
    @Bean
    public IntegrationFlow aggregatedResultCrawlerFlow() {
        return IntegrationFlow
                .fromSupplier(() -> TARGET_URLS,
                        e -> e.poller(Pollers.cron("30 * * * * *")))

                .split()
                .channel(c -> c.executor(Executors.newVirtualThreadPerTaskExecutor()))

                // 🌟 [수정] HTTP 요청 전 헤더를 안전하게 추가
                .enrichHeaders(h -> h.header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))

                .handle(Http.outboundGateway(url -> url.getPayload())
                        .httpMethod(HttpMethod.GET)
                        .expectedResponseType(String.class)
                        .errorHandler(new SafeResponseErrorHandler()))

                .<Object, String>transform(payload -> parseTitle(payload))

                .aggregate()

                .handle(List.class, (titles, headers) -> {
                    System.out.println("==================================================");
                    System.out.println("[집계 흐름] 전체 수집 완료 (총 " + titles.size() + "건)");
                    titles.forEach(t -> System.out.println(" - " + t));
                    System.out.println("==================================================");
                    return null;
                })
                .get();
    }

    // =========================================================================
    // 헬퍼 메소드 & 에러 핸들러
    // =========================================================================

    private String parseTitle(Object payload) {
        if (payload instanceof ResponseEntity<?> response) {
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return "수집 실패 (HTTP 상태 코드: " + response.getStatusCode() + ")";
            }

            try {
                Document doc = Jsoup.parse((String) response.getBody());
                return doc.title();
            } catch (Exception e) {
                return "파싱 에러: " + e.getMessage();
            }
        }

        return "수집 실패: " + payload.toString();
    }

    private static class SafeResponseErrorHandler extends DefaultResponseErrorHandler {
        @Override
        public boolean hasError(ClientHttpResponse response) {
            return false;
        }
    }
}