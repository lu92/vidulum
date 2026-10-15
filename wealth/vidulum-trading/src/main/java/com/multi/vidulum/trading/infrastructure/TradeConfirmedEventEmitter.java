package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.trading.api.TradeConfirmedEvent;
import com.multi.vidulum.trading.api.TradeConfirmedHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes the one event trading sends to the rest of the application.
 *
 * <p>Both of trading's routes end here: the fill of an order, and a trade entered by hand. The
 * topic name comes from {@link TradeConfirmedHandler} rather than from a string, so a consumer and
 * this emitter cannot disagree about it.
 */
@Slf4j
@Component
@AllArgsConstructor
public class TradeConfirmedEventEmitter {

    private final KafkaTemplate<String, TradeConfirmedEvent> tradeConfirmedKafkaTemplate;

    public void emit(TradeConfirmedEvent event) {
        tradeConfirmedKafkaTemplate.send(TradeConfirmedHandler.TOPIC, event);
    }
}
