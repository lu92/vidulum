package com.multi.vidulum.pnl.app.listeners;

import com.multi.vidulum.common.events.UserCreatedEvent;
import com.multi.vidulum.pnl.app.commands.SetupPnlHistoryCommand;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class UserCreatedEventListener {
    private final CommandGateway commandGateway;

    @KafkaListener(
            groupId = "group_id1",
            topics = "user_created",
            containerFactory = "userCreatedContainerFactory")
    public void on(UserCreatedEvent event) {
        log.info("UserCreatedEvent event [{}] has been captured", event);
        SetupPnlHistoryCommand command = SetupPnlHistoryCommand.builder()
                .userId(event.getUserId())
                .build();
        commandGateway.send(command);
        log.info("UserCreatedEvent event [{}] has been processed successfully", event);
    }
}
