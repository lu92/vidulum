package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.BankDataIngestionUnifiedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Emits bank data ingestion events to Kafka.
 * Events are sent with cashFlowId as the partition key to ensure ordering per CashFlow.
 */
@Slf4j
@Component
@AllArgsConstructor
public class BankDataIngestionEventEmitter {

    public static final String TOPIC = "bank_data_ingestion";

    private final KafkaTemplate<String, BankDataIngestionUnifiedEvent> bankDataIngestionKafkaTemplate;

    /**
     * Emits an event to the bank_data_ingestion Kafka topic.
     * Uses cashFlowId as the partition key to guarantee event ordering per CashFlow.
     *
     * @param event the event to emit
     */
    public void emit(BankDataIngestionEvent event) {
        BankDataIngestionUnifiedEvent unifiedEvent = BankDataIngestionUnifiedEvent.builder()
                .metadata(Map.of(
                        "eventType", event.getClass().getSimpleName(),
                        "jobId", event.jobId(),
                        "cashFlowId", event.cashFlowId(),
                        "occurredAt", event.occurredAt().toString()
                ))
                .content(JsonContent.asPrettyJson(event))
                .build();

        // Use cashFlowId as partition key to ensure ordering per CashFlow
        bankDataIngestionKafkaTemplate.send(TOPIC, event.cashFlowId(), unifiedEvent);

        log.info("Event emitted to [{}]: type=[{}], jobId=[{}], cashFlowId=[{}]",
                TOPIC,
                event.getClass().getSimpleName(),
                event.jobId(),
                event.cashFlowId());
    }
}
