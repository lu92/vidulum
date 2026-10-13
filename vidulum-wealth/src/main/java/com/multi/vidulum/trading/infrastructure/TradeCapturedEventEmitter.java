package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.trading.app.TradeCapturedEvent;
import lombok.AllArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class TradeCapturedEventEmitter {
    private final KafkaTemplate<String, TradeCapturedEvent> tradeCapturedKafkaTemplate;

    public void emit(TradeCapturedEvent event) {
        tradeCapturedKafkaTemplate.send(TradeCapturedEvent.TOPIC, event);
    }
}
