package com.multi.vidulum.user_financial_profile.infrastructure;

import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.UserFinancialProfileUnifiedEvent;
import com.multi.vidulum.user_financial_profile.domain.UserFinancialProfileEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@AllArgsConstructor
public class UserFinancialProfileEventEmitter {

    public static final String TOPIC = "user_financial_profile";

    private final KafkaTemplate<String, UserFinancialProfileUnifiedEvent> userFinancialProfileKafkaTemplate;

    public void emit(UserFinancialProfileEvent event) {
        UserFinancialProfileUnifiedEvent envelope = UserFinancialProfileUnifiedEvent.builder()
                .metadata(Map.of("event", event.getClass().getSimpleName()))
                .content(JsonContent.asPrettyJson(event))
                .build();
        log.info("UserFinancialProfile event emitted: [{}]", envelope);
        userFinancialProfileKafkaTemplate.send(TOPIC, event.userId().getId(), envelope);
    }
}
