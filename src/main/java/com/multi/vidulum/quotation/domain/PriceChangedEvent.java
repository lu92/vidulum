package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import lombok.*;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceChangedEvent {
    Ticker ticker;
    Money currentPrice;
    double pctChange;
    ZonedDateTime dateTime;
}
