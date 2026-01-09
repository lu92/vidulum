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

    public String getEventType() {
        return metadata != null ? (String) metadata.get("eventType") : null;
    }

    public String getCashFlowId() {
        return metadata != null ? (String) metadata.get("cashFlowId") : null;
    }

    public String getJobId() {
        return metadata != null ? (String) metadata.get("jobId") : null;
    }
}
