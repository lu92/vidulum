package com.multi.vidulum.common;

import lombok.Value;

import java.time.ZonedDateTime;

@Value
public class PriceChangedEvent {
    Ticker ticker;
    Money currentPrice;
    double pctChange;
    ZonedDateTime dateTime;
}
