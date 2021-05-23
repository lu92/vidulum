package com.multi.vidulum.shared;

import com.multi.vidulum.common.events.TradeAppliedToPortfolioEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class TradeAppliedToPortfolioEventListener {

    @KafkaListener(
            groupId = "group_id1",
            topics = "executed_trades",
            containerFactory = "tradeExecutedContainerFactory")
    public void on(TradeAppliedToPortfolioEvent event) {
        log.info("TradeAppliedToPortfolioEvent: [{}]", event);
    }
}
