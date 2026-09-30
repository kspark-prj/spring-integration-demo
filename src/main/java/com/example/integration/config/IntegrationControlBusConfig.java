package com.example.integration.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.GlobalChannelInterceptor;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.StandardIntegrationFlow;
import org.springframework.integration.endpoint.SourcePollingChannelAdapter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class IntegrationControlBusConfig {

    public static final String CONTROL_BUS_CHANNEL = "controlBusChannel";

    @Bean(name = CONTROL_BUS_CHANNEL)
    public MessageChannel controlBusChannel() {
        return new DirectChannel();
    }

    /**
     * Control Bus IntegrationFlow (SpEL 명령 처리: @beanName.start(), @beanName.stop())
     */
    @Bean
    public IntegrationFlow controlBusFlow() {
        return IntegrationFlow.from(CONTROL_BUS_CHANNEL)
                .controlBus()
                .get();
    }

    /**
     * 인바운드 어댑터 및 메시지 흐름의 실시간 실행 횟수와 마지막 실행 시각을 추적하는 서비스.
     *
     * 동작 원리:
     * - ContextRefreshedEvent 시점(모든 Bean 초기화 완료 후)에 IntegrationFlow 및
     *   SourcePollingChannelAdapter의 MessageChannel 인스턴스 → Bean 이름 매핑 테이블을 구축합니다.
     * - @GlobalChannelInterceptor의 preSend()에서 메시지가 흐르는 채널의 인스턴스를 매핑 테이블에서 조회하여
     *   해당 Flow의 Bean 이름으로 실행 횟수와 마지막 실행 시각을 기록합니다.
     * - DashboardIntegrationController는 동일한 Flow Bean 이름으로 조회하므로 값이 정확히 일치합니다.
     *
     * ※ @PostConstruct가 아닌 ContextRefreshedEvent를 사용하는 이유:
     *    @PostConstruct 시점에는 IntegrationFlowBeanPostProcessor가 Flow 내부 컴포넌트를 아직 Bean으로
     *    등록하지 않았고, MessagingAnnotationPostProcessor도 @InboundChannelAdapter의
     *    SourcePollingChannelAdapter를 아직 생성하지 않은 상태이므로 채널 매핑이 불완전합니다.
     */
    @Component
    @GlobalChannelInterceptor
    public static class AdapterExecutionTracker implements ChannelInterceptor, ApplicationContextAware {

        private static final Logger log = LoggerFactory.getLogger(AdapterExecutionTracker.class);

        private final Map<String, AtomicLong> executionCounts = new ConcurrentHashMap<>();
        private final Map<String, LocalDateTime> lastExecutionTimes = new ConcurrentHashMap<>();

        /**
         * MessageChannel 인스턴스 → Flow/Adapter Bean 이름 매핑.
         * 하나의 채널이 여러 Flow에서 사용될 수 있으므로 Set으로 관리합니다.
         */
        private final Map<MessageChannel, Set<String>> channelToFlowNames = new ConcurrentHashMap<>();

        private ApplicationContext applicationContext;
        private volatile boolean mappingsBuilt = false;

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            this.applicationContext = applicationContext;
        }

        /**
         * 모든 Bean이 완전히 초기화된 ContextRefreshedEvent 시점에 채널 매핑을 구축합니다.
         * 이 시점에는:
         * - IntegrationFlowBeanPostProcessor가 Flow 내부 컴포넌트를 Bean으로 등록 완료
         * - MessagingAnnotationPostProcessor가 @InboundChannelAdapter의 SourcePollingChannelAdapter 생성 완료
         * - 모든 채널의 실제 런타임 인스턴스가 확정된 상태
         */
        @EventListener(ContextRefreshedEvent.class)
        public void buildChannelMappings() {
            if (mappingsBuilt) return;

            // 1. StandardIntegrationFlow 내부의 모든 MessageChannel을 Flow Bean 이름에 매핑
            Map<String, StandardIntegrationFlow> flows = applicationContext.getBeansOfType(StandardIntegrationFlow.class);
            for (Map.Entry<String, StandardIntegrationFlow> entry : flows.entrySet()) {
                String flowBeanName = entry.getKey();
                if ("controlBusFlow".equals(flowBeanName)) continue;

                StandardIntegrationFlow flow = entry.getValue();
                for (Object component : flow.getIntegrationComponents().keySet()) {
                    if (component instanceof MessageChannel mc) {
                        channelToFlowNames
                                .computeIfAbsent(mc, k -> ConcurrentHashMap.newKeySet())
                                .add(flowBeanName);
                        log.debug("[Tracker] Flow 채널 매핑: {} → {}", mc, flowBeanName);
                    }
                }
            }

            // 2. SourcePollingChannelAdapter의 outputChannel을 어댑터 Bean 이름에 매핑
            //    (@InboundChannelAdapter 기반 어댑터 및 Flow 내부 자동 생성 어댑터 모두 포함)
            Map<String, SourcePollingChannelAdapter> adapters = applicationContext.getBeansOfType(SourcePollingChannelAdapter.class);
            for (Map.Entry<String, SourcePollingChannelAdapter> entry : adapters.entrySet()) {
                String adapterBeanName = entry.getKey();
                SourcePollingChannelAdapter adapter = entry.getValue();
                MessageChannel outputChannel = adapter.getOutputChannel();
                if (outputChannel != null) {
                    channelToFlowNames
                            .computeIfAbsent(outputChannel, k -> ConcurrentHashMap.newKeySet())
                            .add(adapterBeanName);
                    log.debug("[Tracker] Adapter 채널 매핑: {} → {}", outputChannel, adapterBeanName);
                }
            }

            mappingsBuilt = true;
            log.info("[Tracker] 채널-Flow 매핑 구축 완료. 총 {}개 채널, {}개 매핑 등록.",
                    channelToFlowNames.size(),
                    channelToFlowNames.values().stream().mapToInt(Set::size).sum());
        }

        public void recordExecution(String key) {
            if (key == null || key.isBlank()) return;
            executionCounts.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            lastExecutionTimes.put(key, LocalDateTime.now());
        }

        public long getExecutionCount(String key) {
            AtomicLong count = executionCounts.get(key);
            return count != null ? count.get() : 0L;
        }

        public LocalDateTime getLastExecutionTime(String key) {
            return lastExecutionTimes.get(key);
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            if (channel != null) {
                Set<String> flowNames = channelToFlowNames.get(channel);
                if (flowNames != null) {
                    for (String flowName : flowNames) {
                        recordExecution(flowName);
                    }
                }
            }
            return message;
        }
    }
}

