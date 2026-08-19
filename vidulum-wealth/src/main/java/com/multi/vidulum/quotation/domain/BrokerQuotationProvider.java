package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@AllArgsConstructor
public abstract class BrokerQuotationProvider {
    @Getter
    protected Broker broker;
    private final ConcurrentHashMap<Symbol, AssetPriceMetadata> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Ticker, AssetBasicInfo> basicInfo = new ConcurrentHashMap<>();


    void onPriceChange(PriceChangedEvent event) {
        AssetPriceMetadata priceMetadata = AssetPriceMetadata.builder()
                .symbol(event.getSymbol())
                .currentPrice(event.getCurrentPrice())
                .pctChange(event.getPctChange())
                .dateTime(event.getDateTime())
                .build();
        cache.put(event.getSymbol(), priceMetadata);
        log.info("[{}] Price of [{}] has been updated to [{}]", getBroker(), event.getSymbol().getId(), event.getCurrentPrice());
    }

    AssetPriceMetadata fetch(Symbol symbol) {
        if (cache.containsKey(symbol)) {
            return cache.get(symbol);
        } else if (symbol.getDestination().equals(Ticker.of("USD"))) {
            AssetPriceMetadata priceMetadata = cache.get(Symbol.of(symbol.getOrigin(), Ticker.of("USDT")));
            if (priceMetadata == null) {
                throw new QuoteNotFoundException(symbol);
            }
            return AssetPriceMetadata.builder()
                    .symbol(Symbol.of(symbol.getOrigin(), Ticker.of("USD")))
                    .currentPrice(priceMetadata.getCurrentPrice())
                    .pctChange(priceMetadata.getPctChange())
                    .dateTime(priceMetadata.getDateTime())
                    .build();
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

    public void registerBasicInfoAboutAsset(AssetBasicInfo assetBasicInfo) {
        basicInfo.put(assetBasicInfo.getTicker(), assetBasicInfo);
        log.info("Basic Info for [{}] updated: [{}]", assetBasicInfo.getTicker(), assetBasicInfo);
    }

    public void clearCaches() {
        cache.clear();
        basicInfo.clear();
    }
}
