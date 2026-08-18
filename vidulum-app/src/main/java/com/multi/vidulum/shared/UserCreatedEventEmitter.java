package com.multi.vidulum.shared;

import com.multi.vidulum.common.events.UserCreatedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class UserCreatedEventEmitter {
    private final KafkaTemplate<String, UserCreatedEvent> userCreatedKafkaTemplate;

    public void emit(UserCreatedEvent event) {
        userCreatedKafkaTemplate.send("user_created", event);
        log.info("emitting event [{}]", event);
    }
}
