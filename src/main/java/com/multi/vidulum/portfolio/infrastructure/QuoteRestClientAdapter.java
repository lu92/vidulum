package com.multi.vidulum.portfolio.infrastructure;

import com.multi.vidulum.common.AssetPriceMetadata;
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
    public AssetPriceMetadata fetch(Ticker ticker) {
        return quotationService.fetch(ticker);
    }

    @Override
    public AssetBasicInfo fetchBasicInfoAboutAsset(Ticker ticker) {
        return quotationService.fetchBasicInfoAboutAsset(ticker);
    }
}
