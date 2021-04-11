package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Ticker;

public interface QuoteRestClient {
    AssetPriceMetadata fetch(Ticker ticker);

    AssetBasicInfo fetchBasicInfoAboutAsset(Ticker ticker);
}
