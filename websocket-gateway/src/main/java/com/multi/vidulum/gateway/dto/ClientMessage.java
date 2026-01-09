package com.multi.vidulum.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message received from WebSocket client.
 *
 * Examples:
 * - Subscribe: { "action": "subscribe", "topic": "bank_data_ingestion", "cashFlowId": "xxx" }
 * - Unsubscribe: { "action": "unsubscribe", "topic": "bank_data_ingestion", "cashFlowId": "xxx" }
 * - Ping: { "action": "ping" }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientMessage {

    private Action action;
    private String topic;
    private String cashFlowId;

    public enum Action {
        subscribe,
        unsubscribe,
        ping
    }
}
