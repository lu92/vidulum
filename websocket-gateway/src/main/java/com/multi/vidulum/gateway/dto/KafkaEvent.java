package com.multi.vidulum.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents an event received from Kafka.
 * This matches the BankDataIngestionUnifiedEvent and CashFlowUnifiedEvent structure.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KafkaEvent {

    private Map<String, Object> metadata;
    private JsonContent content;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonContent {
        private String content;
    }

    /**
     * Get event type from metadata.
     * Supports both "eventType" (bank_data_ingestion events) and "event" (cash_flow events).
     */
    public String getEventType() {
        if (metadata == null) return null;
        // Try "eventType" first (bank_data_ingestion), then "event" (cash_flow)
        String eventType = (String) metadata.get("eventType");
        if (eventType == null) {
            eventType = (String) metadata.get("event");
        }
        return eventType;
    }

    /**
     * Get cashFlowId from metadata or from parsed content.
     * CashFlow events store cashFlowId in the content JSON, not in metadata.
     */
    public String getCashFlowId() {
        if (metadata != null) {
            String cashFlowId = (String) metadata.get("cashFlowId");
            if (cashFlowId != null) {
                return cashFlowId;
            }
        }
        // Try to extract from content for cash_flow events
        return extractCashFlowIdFromContent();
    }

    public String getJobId() {
        return metadata != null ? (String) metadata.get("jobId") : null;
    }

    private String extractCashFlowIdFromContent() {
        if (content == null || content.getContent() == null) {
            return null;
        }
        try {
            // Simple extraction - look for "cashFlowId":{"id":"..."} pattern
            String json = content.getContent();
            int idx = json.indexOf("\"cashFlowId\"");
            if (idx == -1) return null;

            // Find the id value
            int idIdx = json.indexOf("\"id\"", idx);
            if (idIdx == -1) return null;

            int colonIdx = json.indexOf(":", idIdx);
            if (colonIdx == -1) return null;

            int startQuote = json.indexOf("\"", colonIdx);
            if (startQuote == -1) return null;

            int endQuote = json.indexOf("\"", startQuote + 1);
            if (endQuote == -1) return null;

            return json.substring(startQuote + 1, endQuote);
        } catch (Exception e) {
            return null;
        }
    }
}
