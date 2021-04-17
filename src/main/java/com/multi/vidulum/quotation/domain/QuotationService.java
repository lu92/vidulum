package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Symbol;
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
    private final ConcurrentHashMap<Symbol, AssetPriceMetadata> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Ticker, AssetBasicInfo> basicInfo = new ConcurrentHashMap<>();

    @KafkaListener(
            groupId = "group_id1",
            topics = "quotes",
            containerFactory = "priceChangingContainerFactory")
    public void onPriceChange(PriceChangedEvent event) {
        AssetPriceMetadata priceMetadata = AssetPriceMetadata.builder()
                .symbol(event.getSymbol())
                .currentPrice(event.getCurrentPrice())
                .pctChange(event.getPctChange())
                .dateTime(ZonedDateTime.now(clock))
                .build();
        cache.put(event.getSymbol(), priceMetadata);
    }

    public AssetPriceMetadata fetch(Ticker ticker) {
        AssetCategory category = clarifyCategory(ticker);
        switch (category) {
            case FIAT:

                break;
            case CRYPTO_CURRENCY:
                if (ticker.equals(Ticker.of("USDT"))) return null;
        }
        Symbol symbol = clarifySymbol(ticker);
        return fetch(symbol);
    }

    private AssetCategory clarifyCategory(Ticker ticker) {
        if (ticker.equals(Ticker.of("BTC"))) return AssetCategory.CRYPTO_CURRENCY;
        if (ticker.equals(Ticker.of("USD"))) return AssetCategory.FIAT;
        return null;
    }


    private Symbol clarifySymbol(Ticker ticker) {
        return Symbol.of(ticker, Ticker.of("USDT"));
    }

    public AssetPriceMetadata fetch(Symbol symbol) {
        if (cache.containsKey(symbol)) {
            return cache.get(symbol);
        } else {
            throw new QuoteNotFoundException(symbol);
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
