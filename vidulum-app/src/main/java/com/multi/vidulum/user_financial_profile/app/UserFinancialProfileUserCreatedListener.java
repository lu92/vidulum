package com.multi.vidulum.user_financial_profile.app;

import com.multi.vidulum.common.events.UserCreatedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens to {@code user_created} Kafka topic and creates an empty
 * UserFinancialProfile for newly registered users.
 *
 * <p>This replaces the previous synchronous call from RegisterUserCommandHandler.
 * The profile creation is now event-driven and asynchronous.</p>
 */
@Slf4j
@Component
@AllArgsConstructor
public class UserFinancialProfileUserCreatedListener {

    private final UserFinancialProfileService userFinancialProfileService;

    @KafkaListener(
            groupId = "user_financial_profile_user_created_group",
            topics = "user_created",
            containerFactory = "userCreatedContainerFactory")
    public void on(UserCreatedEvent event) {
        log.info("UserCreatedEvent captured for user [{}] — creating financial profile", event.getUserId().getId());
        userFinancialProfileService.createEmptyProfile(event.getUserId());
        log.info("UserCreatedEvent processed — financial profile created for user [{}]", event.getUserId().getId());
    }
}
