package com.multi.vidulum.portfolio.infrastructure;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.quotation.domain.QuotationService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class QuoteRestClientAdapter implements QuoteRestClient {

    private final QuotationService quotationService;

    @Override
    public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
        return quotationService.fetch(broker, symbol);
    }

    @Override
    public AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker) {
        return quotationService.fetchBasicInfoAboutAsset(broker, ticker);
    }
}
