package com.multi.vidulum.common;

import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class AssetPriceMetadata {
    Ticker ticker;
    Money currentPrice;
    double pctChange;
    ZonedDateTime dateTime;
}
