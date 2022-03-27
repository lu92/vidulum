package com.multi.vidulum.common.events;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.TradeId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeCapturedEvent {
    OrderId orderId;
    TradeId tradeId;
    Quantity quantity;
    Price price;
    ZonedDateTime dateTime;
}
