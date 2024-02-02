package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class CashFlowEventEmitter {
    private final KafkaTemplate<String, CashFlowUnifiedEvent> cashFlowUnifiedEventKafkaTemplate;

    public void emit(CashFlowUnifiedEvent event) {
        log.info("Event emitted: [{}]", event);
        cashFlowUnifiedEventKafkaTemplate.send("cash_flow", event);
    }
}
