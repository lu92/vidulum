package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceChangedEvent {
    private Ticker ticker;
    private Money currentPrice;
    private double pctChange;
    private ZonedDateTime dateTime;
}
