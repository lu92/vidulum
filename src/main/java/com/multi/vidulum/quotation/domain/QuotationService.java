package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import lombok.AllArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;

@AllArgsConstructor
public class QuotationService {
    private final Clock clock;
    private final ConcurrentHashMap<Ticker, AssetPriceMetadata> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Ticker, AssetBasicInfo> basicInfo = new ConcurrentHashMap<>();

    @KafkaListener(
            groupId = "group_id1",
            topics = "quotes",
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

    public AssetBasicInfo fetchBasicInfoAboutAsset(Ticker ticker) {
        if (basicInfo.containsKey(ticker)) {
            return basicInfo.get(ticker);
        } else {
            return AssetBasicInfo.notFound(ticker);
        }
    }
}
