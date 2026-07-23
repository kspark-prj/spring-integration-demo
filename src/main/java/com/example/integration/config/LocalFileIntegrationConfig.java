package com.example.integration.config;

import com.example.integration.service.FileProcessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.InboundChannelAdapter;
import org.springframework.integration.annotation.Poller;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.integration.file.filters.ChainFileListFilter;
import org.springframework.integration.file.filters.FileSystemPersistentAcceptOnceFileListFilter;
import org.springframework.integration.file.filters.LastModifiedFileListFilter;
import org.springframework.integration.metadata.SimpleMetadataStore;

import java.io.File;
import java.time.Duration;

/**
 * [요구사항 1] Local Folder Watcher 설정
 * - 로컬 디렉터리를 감시하며, 파일 작성 중(복사 중) 방어 로직을 적용합니다.
 * - 완전히 작성이 완료된 파일만 읽어 들여 서비스로 전달합니다.
 */
@Configuration
public class LocalFileIntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalFileIntegrationConfig.class);

    @Value("${demo.local.watch-dir}")
    private String watchDir;

    @Value("${demo.local.age-threshold-seconds}")
    private long ageThresholdSeconds;

    @Value("${demo.local.polling-rate-ms}")
    private long pollingRateMs;

    /**
     * 파일 감시용 Inbound Message Source
     * FileReadingMessageSource는 지정된 디렉터리를 스캔하여 파일을 메시지로 생성합니다.
     */
    @Bean
    public MessageSource<File> localFileReadingMessageSource() {
        FileReadingMessageSource source = new FileReadingMessageSource();
        
        // 감시 디렉터리 생성 및 설정
        File directory = new File(watchDir);
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            log.info("로컬 감시 디렉터리 생성 여부: {} ({})", created, watchDir);
        }
        source.setDirectory(directory);

        // 방어 로직 (Incomplete File Prevention) 체인 구성
        ChainFileListFilter<File> filterChain = new ChainFileListFilter<>();

        /*
         * 방어 1: LastModifiedFileListFilter
         * 파일의 마지막 수정 시각(lastModified)이 현재 시각 기준 최소 'ageThresholdSeconds' 초 이전인 파일만 통과시킵니다.
         * 파일 복사 중이나 대용량 파일 생성 도중에는 수정 시각이 현재 시각과 가깝거나 계속 변경되므로,
         * 복사가 완전히 끝날 때까지 처리를 대기하게 만드는 강력한 방어 필터입니다.
         */
        LastModifiedFileListFilter ageFilter = new LastModifiedFileListFilter();
        ageFilter.setAge(Duration.ofSeconds(ageThresholdSeconds));
        
        /*
         * 방어 2: FileSystemPersistentAcceptOnceFileListFilter
         * 처리 완료된 파일의 파일명과 수정 시각 정보를 메타데이터 스토어에 기록하여,
         * 동일한 파일이 중복으로 폴링되어 처리되는 것을 방지합니다.
         * (SimpleMetadataStore는 메모리 기반이며, 실운영 환경에서는 Redis, Database 기반 MetadataStore 사용 권장)
         */
        FileSystemPersistentAcceptOnceFileListFilter duplicateFilter = 
                new FileSystemPersistentAcceptOnceFileListFilter(new SimpleMetadataStore(), "localFileFilter");

        filterChain.addFilter(duplicateFilter);
        filterChain.addFilter(ageFilter);

        source.setFilter(filterChain);
        return source;
    }

    /**
     * Local Folder Watcher IntegrationFlow
     * - 주기적으로 localFileReadingMessageSource를 폴링합니다.
     * - 감지된 파일의 File 객체를 수신하고 내용을 읽어 로그로 출력하는 비즈니스 로직(FileProcessService)으로 라우팅합니다.
     */
    @Bean
    public IntegrationFlow localFileWatchFlow(FileProcessService fileProcessService) {
        return IntegrationFlow.from(localFileReadingMessageSource(), 
                        c -> c.poller(Pollers.fixedDelay(Duration.ofMillis(pollingRateMs))))
                // Payload는 java.io.File 타입입니다.
                .handle(fileProcessService, "processLocalFile")
                .get();
    }
}
