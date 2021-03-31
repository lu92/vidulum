package com.multi.vidulum.portfolio.infrastructure;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import org.springframework.stereotype.Component;

@Component
public class QuoteRestClientAdapter implements QuoteRestClient {
    @Override
    public AssetPriceMetadata fetch(Ticker ticker) {
        return null;
    }
}
