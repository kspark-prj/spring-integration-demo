package com.example.integration.interceptor;

import org.slf4j.MDC;
import org.springframework.integration.context.IntegrationObjectSupport;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;

public class MdcLogInterceptor implements ChannelInterceptor {

    public static final String LOGIC_TYPE_HEADER = "LOGIC_TYPE";
    private static final String MDC_KEY = "logicType";
    private static final String DEFAULT_LOGIC_TYPE = "integration-default";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // 1. 메시지 헤더에 LOGIC_TYPE이 지정되어 있으면 우선 사용
        String logicType = message.getHeaders().get(LOGIC_TYPE_HEADER, String.class);

        // 2. 헤더에 없으면 채널 이름에서 자동 추출 (공통 처리)
        if (logicType == null) {
            logicType = resolveLogicTypeFromChannel(channel);
        }

        // MDC 세팅
        MDC.put(MDC_KEY, logicType);
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        // 메시지 처리가 끝나거나 예외 발생 시 반드시 스레드에서 MDC 제거 (스레드 풀 오염 방지)
        MDC.remove(MDC_KEY);
    }

    private String resolveLogicTypeFromChannel(MessageChannel channel) {
        if (channel instanceof IntegrationObjectSupport integrationChannel) {
            String componentName = integrationChannel.getComponentName();
            if (componentName != null && !componentName.isBlank()) {
                return extractPrefix(componentName);
            }
        }
        return DEFAULT_LOGIC_TYPE;
    }

    private String extractPrefix(String channelName) {
        // 예: orderInputChannel -> order, paymentProcessChannel -> payment
        String[] parts = channelName.split("(?=[A-Z])|[-_]");
        if (parts.length > 0 && !parts[0].isBlank()) {
            return parts[0].toLowerCase();
        }
        return DEFAULT_LOGIC_TYPE;
    }
}