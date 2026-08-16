package com.multi.vidulum.portfolio.infrastructure;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.quotation.domain.QuotationService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class QuoteRestClientAdapter implements QuoteRestClient {

    private final QuotationService quotationService;

    @Override
    public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
        log.info("[{}] Getting price metadata of [{}]", broker, symbol);
        return quotationService.fetch(broker, symbol);
    }

    @Override
    public AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker) {
        log.info("[{}] Getting info about asset [{}]", broker, ticker);
        return quotationService.fetchBasicInfoAboutAsset(broker, ticker);
    }

    @Override
    public void registerBasicInfoAboutAsset(Broker broker, AssetBasicInfo assetBasicInfo) {
        quotationService.registerAssetBasicInfo(broker, assetBasicInfo);
    }
}
