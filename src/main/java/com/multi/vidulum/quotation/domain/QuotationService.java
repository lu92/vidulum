package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Ticker;
import lombok.AllArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;

@AllArgsConstructor
public class QuotationService {
    private final Clock clock;
    private final ConcurrentHashMap<Ticker, AssetPriceMetadata> cache = new ConcurrentHashMap<>();

    @KafkaListener(
            groupId = "group_id1",
            topics =  "quotes",
            containerFactory = "priceChangingContainerFactory")
    public void onPriceChange(PriceChangedEvent event) {
        AssetPriceMetadata priceMetadata = AssetPriceMetadata.builder()
                .ticker(event.getTicker())
                .currentPrice(event.getCurrentPrice())
                .pctChange(event.getPctChange())
                .dateTime(ZonedDateTime.now(clock))
                .build();
        cache.put(event.getTicker(), priceMetadata);
    }

    public AssetPriceMetadata fetch(Ticker ticker) {
        if (cache.containsKey(ticker)) {
            return cache.get(ticker);
        } else {
            throw new QuoteNotFoundException(ticker);
        }
    }
}
