package com.multi.vidulum.shared;

import com.multi.vidulum.common.events.OrderCreatedEvent;
import lombok.AllArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class OrderCreatedEventEmitter {
    private final KafkaTemplate<String, OrderCreatedEvent> orderCreatedEventKafkaTemplate;

    public void emit(OrderCreatedEvent event) {
        orderCreatedEventKafkaTemplate.send("order_created", event);
    }
}
