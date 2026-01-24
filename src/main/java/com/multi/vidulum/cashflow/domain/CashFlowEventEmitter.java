package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

@Slf4j
@Component
@AllArgsConstructor
public class CashFlowEventEmitter {
    private final KafkaTemplate<String, CashFlowUnifiedEvent> cashFlowUnifiedEventKafkaTemplate;

    /**
     * Emit event without key (legacy behavior).
     * Events will be distributed across partitions without ordering guarantee.
     */
    public void emit(CashFlowUnifiedEvent event) {
        log.info("Event emitted: [{}]", event);
        cashFlowUnifiedEventKafkaTemplate.send("cash_flow", event);
    }

    /**
     * Emit event with cashFlowId as the message key.
     * All events for the same cashFlowId will go to the same partition,
     * guaranteeing ordering within that cashFlow.
     *
     * This method blocks until the message is acknowledged by Kafka.
     */
    public void emitWithKey(CashFlowId cashFlowId, CashFlowUnifiedEvent event) {
        String key = cashFlowId.id();
        log.info("Event emitted with key [{}]: [{}]", key, event);
        try {
            cashFlowUnifiedEventKafkaTemplate.send("cash_flow", key, event).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while sending Kafka event", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to send Kafka event", e.getCause());
        }
    }
}
