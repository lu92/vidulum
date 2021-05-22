package com.multi.vidulum.shared;

import com.multi.vidulum.common.events.TradeStoredEvent;
import lombok.AllArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class TradeStoredEventEmitter {
    private final KafkaTemplate<String, TradeStoredEvent> tradeStoredKafkaTemplate;

    public void emit(TradeStoredEvent event) {
        tradeStoredKafkaTemplate.send("trade_stored", event);
    }
}
