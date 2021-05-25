package com.multi.vidulum.shared;

import com.multi.vidulum.common.events.TradeAppliedToPortfolioEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
@Component
@AllArgsConstructor
public class TradeAppliedToPortfolioEventListener {


    private Set<Consumer<TradeAppliedToPortfolioEvent>> callbacks = new HashSet<>();

    @KafkaListener(
            groupId = "group_id1",
            topics = "executed_trades",
            containerFactory = "tradeExecutedContainerFactory")
    public void on(TradeAppliedToPortfolioEvent event) {
        log.info("Event catched: [{}]", event);
        log.info("internal registered callbacks: [{}]", callbacks);
        callbacks.forEach(callback -> {
            log.info("lets call callback [{}]", callback);
            callback.accept(event);
        });
    }

    public void registerCallback(Consumer<TradeAppliedToPortfolioEvent> callback) {
        callbacks.add(callback);
        log.info("Registered callbacks: [{}]", callbacks);
    }

    public void clearCallbacks() {
        callbacks.clear();
    }
}
