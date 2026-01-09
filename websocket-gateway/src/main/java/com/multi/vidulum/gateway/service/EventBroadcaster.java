package com.multi.vidulum.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.gateway.dto.KafkaEvent;
import com.multi.vidulum.gateway.dto.ServerMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Broadcasts Kafka events to subscribed WebSocket clients.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventBroadcaster {

    private final SubscriptionManager subscriptionManager;
    private final ObjectMapper objectMapper;

    public void broadcast(String topic, KafkaEvent event) {
        String cashFlowId = event.getCashFlowId();
        if (cashFlowId == null) {
            log.warn("Received event without cashFlowId, skipping broadcast: topic={}, type={}",
                    topic, event.getEventType());
            return;
        }

        Set<WebSocketSession> subscribers = subscriptionManager.getSubscribers(topic, cashFlowId);
        if (subscribers.isEmpty()) {
            log.debug("No subscribers for topic={}, cashFlowId={}", topic, cashFlowId);
            return;
        }

        ServerMessage message = createServerMessage(topic, event);
        String messageJson;
        try {
            messageJson = objectMapper.writeValueAsString(message);
        } catch (IOException e) {
            log.error("Failed to serialize message: {}", e.getMessage());
            return;
        }

        TextMessage textMessage = new TextMessage(messageJson);
        int successCount = 0;
        int failCount = 0;

        for (WebSocketSession session : subscribers) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                    successCount++;
                }
            } catch (IOException e) {
                log.warn("Failed to send message to session {}: {}", session.getId(), e.getMessage());
                failCount++;
            }
        }

        log.debug("Broadcast complete: topic={}, cashFlowId={}, eventType={}, sent={}, failed={}",
                topic, cashFlowId, event.getEventType(), successCount, failCount);
    }

    private ServerMessage createServerMessage(String topic, KafkaEvent event) {
        Map<String, Object> eventData = parseEventContent(event);

        return ServerMessage.event(
                topic,
                event.getEventType(),
                event.getCashFlowId(),
                eventData
        );
    }

    private Map<String, Object> parseEventContent(KafkaEvent event) {
        if (event.getContent() == null || event.getContent().getContent() == null) {
            return event.getMetadata();
        }

        try {
            return objectMapper.readValue(
                    event.getContent().getContent(),
                    new TypeReference<Map<String, Object>>() {}
            );
        } catch (IOException e) {
            log.warn("Failed to parse event content, using metadata: {}", e.getMessage());
            return event.getMetadata();
        }
    }
}
