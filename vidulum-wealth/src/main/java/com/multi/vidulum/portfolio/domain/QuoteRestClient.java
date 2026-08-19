package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;

public interface QuoteRestClient {
    AssetPriceMetadata fetch(Broker broker, Symbol symbol);

    AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker);

    void registerBasicInfoAboutAsset(Broker broker, AssetBasicInfo assetBasicInfo);
}
