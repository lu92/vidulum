package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;

public interface QuoteRestClient {
    AssetPriceMetadata fetch(Ticker ticker);
    AssetPriceMetadata fetch(Symbol symbol);

    AssetBasicInfo fetchBasicInfoAboutAsset(Ticker ticker);
}
