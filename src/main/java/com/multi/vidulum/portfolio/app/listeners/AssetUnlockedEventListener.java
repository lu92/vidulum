package com.multi.vidulum.portfolio.app.listeners;

import com.multi.vidulum.common.events.AssetUnlockedEvent;
import com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommand;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class AssetUnlockedEventListener {

    private final CommandGateway commandGateway;

    @KafkaListener(
            groupId = "group_id1",
            topics = "asset_unlocked",
            containerFactory = "assetUnlockedContainerFactory")
    public void on(AssetUnlockedEvent event) {
        log.info("AssetUnlockedEvent [{}] has been captured", event);

        UnlockAssetCommand command = UnlockAssetCommand.builder()
                .portfolioId(event.getPortfolioId())
                .ticker(event.getTicker())
                .quantity(event.getQuantity())
                .build();

        commandGateway.send(command);
    }
}
