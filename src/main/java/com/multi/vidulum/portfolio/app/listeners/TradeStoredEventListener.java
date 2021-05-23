package com.multi.vidulum.portfolio.app.listeners;

import com.multi.vidulum.common.events.TradeStoredEvent;
import com.multi.vidulum.portfolio.app.commands.update.ApplyTradeCommand;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class TradeStoredEventListener {

    private final CommandGateway commandGateway;

    @KafkaListener(
            groupId = "group_id1",
            topics = "trade_stored",
            containerFactory = "tradeStoredContainerFactory")
    public void on(TradeStoredEvent event) {
        log.info("StoredTrade event [{}] has been captured", event);

        ApplyTradeCommand command = ApplyTradeCommand.builder()
                .trade(event.getTrade())
                .build();

        commandGateway.send(command);
    }
}
