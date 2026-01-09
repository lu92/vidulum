package com.multi.vidulum.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Message sent to WebSocket client.
 *
 * Examples:
 * - Event: { "type": "event", "topic": "bank_data_ingestion", "eventType": "ImportProgressEvent", "data": {...} }
 * - Ack: { "type": "ack", "action": "subscribe", "success": true }
 * - Pong: { "type": "pong" }
 * - Error: { "type": "error", "message": "Invalid subscription" }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerMessage {

    private Type type;
    private String topic;
    private String eventType;
    private String cashFlowId;
    private Map<String, Object> data;
    private String action;
    private Boolean success;
    private String message;
    private ZonedDateTime timestamp;

    public enum Type {
        event,
        ack,
        pong,
        error
    }

    public static ServerMessage pong() {
        return ServerMessage.builder()
                .type(Type.pong)
                .timestamp(ZonedDateTime.now())
                .build();
    }

    public static ServerMessage ack(String action, boolean success) {
        return ServerMessage.builder()
                .type(Type.ack)
                .action(action)
                .success(success)
                .timestamp(ZonedDateTime.now())
                .build();
    }

    public static ServerMessage ack(String action, boolean success, String message) {
        return ServerMessage.builder()
                .type(Type.ack)
                .action(action)
                .success(success)
                .message(message)
                .timestamp(ZonedDateTime.now())
                .build();
    }

    public static ServerMessage error(String message) {
        return ServerMessage.builder()
                .type(Type.error)
                .message(message)
                .timestamp(ZonedDateTime.now())
                .build();
    }

    public static ServerMessage event(String topic, String eventType, String cashFlowId, Map<String, Object> data) {
        return ServerMessage.builder()
                .type(Type.event)
                .topic(topic)
                .eventType(eventType)
                .cashFlowId(cashFlowId)
                .data(data)
                .timestamp(ZonedDateTime.now())
                .build();
    }
}
