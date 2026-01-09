package com.multi.vidulum.gateway.service;

import com.multi.vidulum.gateway.dto.KafkaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Consumes events from Kafka topics and routes them to WebSocket clients.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaEventConsumer {

    public static final String TOPIC_BANK_DATA_INGESTION = "bank_data_ingestion";
    public static final String TOPIC_CASH_FLOW = "cash_flow";

    private final EventBroadcaster eventBroadcaster;

    @KafkaListener(
            topics = TOPIC_BANK_DATA_INGESTION,
            containerFactory = "kafkaEventListenerContainerFactory"
    )
    public void consumeBankDataIngestionEvent(KafkaEvent event) {
        log.debug("Received bank_data_ingestion event: type={}, cashFlowId={}",
                event.getEventType(), event.getCashFlowId());

        eventBroadcaster.broadcast(TOPIC_BANK_DATA_INGESTION, event);
    }

    @KafkaListener(
            topics = TOPIC_CASH_FLOW,
            containerFactory = "kafkaEventListenerContainerFactory"
    )
    public void consumeCashFlowEvent(KafkaEvent event) {
        log.info("RAW cash_flow event: metadata={}, content={}",
                event.getMetadata(), event.getContent());
        log.debug("Received cash_flow event: type={}, cashFlowId={}",
                event.getEventType(), event.getCashFlowId());

        eventBroadcaster.broadcast(TOPIC_CASH_FLOW, event);
    }
}
