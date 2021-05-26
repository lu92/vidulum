package com.multi.vidulum.shared;

import com.multi.vidulum.common.events.TradeAppliedToPortfolioEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class TradeAppliedToPortfolioEventEmitter {
    private final KafkaTemplate<String, TradeAppliedToPortfolioEvent> tradeExecutedKafkaTemplate;


    public void emit(TradeAppliedToPortfolioEvent event) {
        tradeExecutedKafkaTemplate.send("executed_trades", event);
        log.info("emitting event [{}]", event);
    }
}
