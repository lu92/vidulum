package com.multi.vidulum.quotation.api;

import com.multi.vidulum.shared.ddd.event.IntegrationEvent;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Symbol;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceChangedEvent implements IntegrationEvent {
    private Broker broker;
    private Symbol symbol;
    private Price currentPrice;
    private double pctChange;
    private ZonedDateTime dateTime;
}
