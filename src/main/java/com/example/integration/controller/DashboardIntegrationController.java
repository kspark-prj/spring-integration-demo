package com.example.integration.controller;

import com.example.integration.config.IntegrationControlBusConfig.AdapterExecutionTracker;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.ResponseEntity;
import org.springframework.integration.dsl.StandardIntegrationFlow;
import org.springframework.integration.endpoint.SourcePollingChannelAdapter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 인바운드 어댑터 대시보드 REST API 컨트롤러
 * - 사용자 정의 IntegrationFlow 및 SourcePollingChannelAdapter만 노출합니다.
 * - Spring이 자동 생성하는 내부 ConsumerEndpointFactoryBean 등은 필터링하여 제외합니다.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardIntegrationController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ApplicationContext applicationContext;
    private final MessageChannel controlBusChannel;
    private final AdapterExecutionTracker executionTracker;

    public DashboardIntegrationController(ApplicationContext applicationContext,
                                          MessageChannel controlBusChannel,
                                          AdapterExecutionTracker executionTracker) {
        this.applicationContext = applicationContext;
        this.controlBusChannel = controlBusChannel;
        this.executionTracker = executionTracker;
    }

    @GetMapping("/adapters")
    public ResponseEntity<List<Map<String, Object>>> getInboundAdapters() {
        List<Map<String, Object>> adapters = new ArrayList<>();

        // 1. 사용자 정의 IntegrationFlow Bean 수집
        Map<String, StandardIntegrationFlow> flowBeans = applicationContext.getBeansOfType(StandardIntegrationFlow.class);
        for (Map.Entry<String, StandardIntegrationFlow> entry : flowBeans.entrySet()) {
            String beanName = entry.getKey();
            StandardIntegrationFlow flow = entry.getValue();

            // Control Bus Flow는 대시보드 목록에서 제외
            if (beanName.equals("controlBusFlow")) {
                continue;
            }

            String type = classifyFlowType(beanName);
            String status = resolveFlowRunningStatus(flow) ? "RUNNING" : "STOPPED";
            long totalCount = executionTracker.getExecutionCount(beanName);
            String lastExecutedTime = formatLastExecutedTime(executionTracker.getLastExecutionTime(beanName));

            adapters.add(buildAdapterData(beanName, type, status, totalCount, lastExecutedTime));
        }

        // 2. @InboundChannelAdapter 어노테이션 기반 SourcePollingChannelAdapter 수집
        Map<String, SourcePollingChannelAdapter> pollerBeans = applicationContext.getBeansOfType(SourcePollingChannelAdapter.class);
        for (Map.Entry<String, SourcePollingChannelAdapter> entry : pollerBeans.entrySet()) {
            String beanName = entry.getKey();
            SourcePollingChannelAdapter adapter = entry.getValue();

            // IntegrationFlow에서 이미 등록한 내부 자동 생성 어댑터는 제외
            boolean alreadyRegistered = adapters.stream()
                    .anyMatch(a -> beanName.startsWith((String) a.get("name")));
            if (alreadyRegistered) {
                continue;
            }

            // Bean 이름에서 사용자 친화적 이름 추출
            String displayName = extractDisplayName(beanName);
            String status = adapter.isRunning() ? "RUNNING" : "STOPPED";
            long totalCount = executionTracker.getExecutionCount(beanName);
            String lastExecutedTime = formatLastExecutedTime(executionTracker.getLastExecutionTime(beanName));

            Map<String, Object> data = buildAdapterData(displayName, "InboundChannelAdapter (Cron)", status, totalCount, lastExecutedTime);
            data.put("controlName", beanName); // Control Bus에서 사용할 실제 Bean 이름
            adapters.add(data);
        }

        // 이름 순 정렬
        adapters.sort(Comparator.comparing(a -> (String) a.get("name")));
        return ResponseEntity.ok(adapters);
    }

    @PostMapping("/adapters/{name}/{action}")
    public ResponseEntity<Map<String, Object>> controlAdapter(
            @PathVariable("name") String name,
            @PathVariable("action") String action) {

        String lowerAction = action.toLowerCase();
        if (!"start".equals(lowerAction) && !"stop".equals(lowerAction)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Invalid action: " + action + ". Use 'start' or 'stop'."
            ));
        }

        try {
            // Control Bus 채널(controlBusChannel)을 통한 SpEL 명령 발송
            String spelCommand = "@" + name + "." + lowerAction + "()";
            Message<String> message = MessageBuilder.withPayload(spelCommand).build();
            controlBusChannel.send(message);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "name", name,
                    "action", lowerAction,
                    "message", "Control command '" + spelCommand + "' issued successfully."
            ));
        } catch (Exception e) {
            // Control Bus 실패 시 직접 SmartLifecycle 빈 제어 시도
            try {
                if (applicationContext.containsBean(name)) {
                    Object bean = applicationContext.getBean(name);
                    if (bean instanceof SmartLifecycle lifecycle) {
                        if ("start".equals(lowerAction)) lifecycle.start();
                        else lifecycle.stop();

                        return ResponseEntity.ok(Map.of(
                                "success", true,
                                "name", name,
                                "action", lowerAction,
                                "message", "Direct lifecycle control executed for '" + name + "'."
                        ));
                    }
                }
            } catch (Exception fallbackEx) {
                // fallback 실패 시 원래 에러 메시지 반환
            }

            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Failed to execute control command for '" + name + "': " + e.getMessage()
            ));
        }
    }

    /**
     * IntegrationFlow Bean 이름 기반 유형 분류
     */
    private String classifyFlowType(String beanName) {
        String lower = beanName.toLowerCase();
        if (lower.contains("crawler")) return "HTTP Crawler Flow";
        if (lower.contains("localfile") || lower.contains("filewatch")) return "File Watcher Flow";
        if (lower.contains("sftpupload")) return "SFTP Outbound Flow";
        if (lower.contains("sftpinbound") || lower.contains("sftptodbimport")) return "SFTP Inbound Flow";
        if (lower.contains("dbtosftp") || lower.contains("export")) return "DB Export Flow";
        if (lower.contains("tcp")) return "TCP/IP Flow";
        return "IntegrationFlow";
    }

    /**
     * SourcePollingChannelAdapter의 Spring 생성 Bean 이름에서 사용자 친화적 이름 추출
     */
    private String extractDisplayName(String beanName) {
        // 예: "cronIntegrationConfig.generateCronMessage.inboundChannelAdapter" -> "generateCronMessage"
        if (beanName.contains(".")) {
            String[] parts = beanName.split("\\.");
            if (parts.length >= 2) {
                return parts[parts.length - 2]; // 메소드 명 부분 추출
            }
        }
        return beanName;
    }

    private Map<String, Object> buildAdapterData(String name, String type, String status, long totalCount, String lastExecutedTime) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("type", type);
        data.put("status", status);
        data.put("totalCount", totalCount);
        data.put("lastExecutedTime", lastExecutedTime);
        return data;
    }

    /**
     * StandardIntegrationFlow의 실제 실행 상태를 판별합니다.
     *
     * Spring Integration 설계상 StandardIntegrationFlow.isAutoStartup()은 false를 반환합니다.
     * 내부 컴포넌트(SourcePollingChannelAdapter, EventDrivenConsumer 등)는 각각 독립 Bean으로
     * 등록되어 Spring Lifecycle Processor가 개별적으로 start()를 호출하므로,
     * Flow 자체의 start()는 호출되지 않아 flow.isRunning()은 항상 false입니다.
     *
     * 따라서 Flow 내부의 SmartLifecycle 컴포넌트 중 하나라도 running 상태이면 RUNNING으로 판정합니다.
     */
    private boolean resolveFlowRunningStatus(StandardIntegrationFlow flow) {
        for (Object component : flow.getIntegrationComponents().keySet()) {
            if (component instanceof SmartLifecycle lifecycle && lifecycle.isRunning()) {
                return true;
            }
        }
        return false;
    }

    private String formatLastExecutedTime(LocalDateTime time) {
        return time != null ? time.format(DATE_FORMATTER) : "N/A";
    }
}
