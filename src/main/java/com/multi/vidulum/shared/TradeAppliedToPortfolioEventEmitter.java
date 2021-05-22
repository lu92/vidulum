package com.multi.vidulum.shared;

import com.multi.vidulum.common.events.TradeAppliedToPortfolioEvent;
import lombok.AllArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class TradeAppliedToPortfolioEventEmitter {
    private final KafkaTemplate<String, TradeAppliedToPortfolioEvent> tradeExecutedKafkaTemplate;


    public void emit(TradeAppliedToPortfolioEvent event) {
        tradeExecutedKafkaTemplate.send("trade_executed", event);
    }
}
