package com.multi.vidulum.common;

import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class AssetPriceMetadata {
    Symbol symbol;
    Price currentPrice;
    double pctChange;
    ZonedDateTime dateTime;

    /**
     * How this price was arrived at. Defaults to {@link PriceOrigin#DIRECT} so a builder written
     * before B4 keeps saying what it always meant.
     */
    @Builder.Default
    PriceOrigin origin = PriceOrigin.DIRECT;
}
