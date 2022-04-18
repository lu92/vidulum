package com.multi.vidulum.shared;

import com.multi.vidulum.common.events.OrderFilledEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class OrderFilledEventEmitter {
    private final KafkaTemplate<String, OrderFilledEvent> orderFilledKafkaTemplate;

    public void emit(OrderFilledEvent event) {
        orderFilledKafkaTemplate.send("order_filled", event);
    }

}
