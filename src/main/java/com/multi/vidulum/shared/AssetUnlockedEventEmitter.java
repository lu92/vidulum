package com.multi.vidulum.shared;

import com.multi.vidulum.common.events.AssetUnlockedEvent;
import lombok.AllArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class AssetUnlockedEventEmitter {
    private final KafkaTemplate<String, AssetUnlockedEvent> assetUnlockedEventKafkaTemplate;

    public void emit(AssetUnlockedEvent event) {
        assetUnlockedEventKafkaTemplate.send("asset_unlocked", event);
    }
}
