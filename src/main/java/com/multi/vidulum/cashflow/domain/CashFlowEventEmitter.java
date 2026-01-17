package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@AllArgsConstructor
public class CashFlowEventEmitter {

    private static final int KAFKA_SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, CashFlowUnifiedEvent> cashFlowUnifiedEventKafkaTemplate;

    /**
     * Emits a CashFlowUnifiedEvent to Kafka synchronously.
     * <p>
     * This method blocks until Kafka broker acknowledges receipt of the message,
     * ensuring proper event ordering for operations like bank data import where
     * CategoryCreatedEvents must be processed before HistoricalCashChangeImportedEvents.
     *
     * @param event the event to emit
     * @throws RuntimeException if Kafka send fails or times out
     */
    public void emit(CashFlowUnifiedEvent event) {
        log.info("Event emitting: [{}]", event);
        try {
            cashFlowUnifiedEventKafkaTemplate.send("cash_flow", event)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Event sent to Kafka: [{}]", event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kafka send interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Kafka send failed: " + e.getMessage(), e);
        } catch (TimeoutException e) {
            throw new RuntimeException("Kafka send timed out after " + KAFKA_SEND_TIMEOUT_SECONDS + " seconds", e);
        }
    }
}
