package com.multi.vidulum.common.events;

import com.multi.vidulum.common.JsonContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Unified event wrapper for bank data ingestion events sent to Kafka.
 * Similar to CashFlowUnifiedEvent but for the bank_data_ingestion topic.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankDataIngestionUnifiedEvent {
    Map<String, Object> metadata;
    JsonContent content;
}
