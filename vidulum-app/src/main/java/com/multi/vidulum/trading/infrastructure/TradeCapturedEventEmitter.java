package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.events.TradeCapturedEvent;
import lombok.AllArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class TradeCapturedEventEmitter {
    private final KafkaTemplate<String, TradeCapturedEvent> tradeCapturedKafkaTemplate;

    public void emit(TradeCapturedEvent event) {
        tradeCapturedKafkaTemplate.send("trade_captured", event);
    }
}
