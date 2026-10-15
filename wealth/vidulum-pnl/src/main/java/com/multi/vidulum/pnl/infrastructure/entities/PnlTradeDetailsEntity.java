package com.multi.vidulum.pnl.infrastructure.entities;

import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;

import java.util.Date;

@Builder
@Getter
@Value
@ToString
public class PnlTradeDetailsEntity {
    String originTradeId;
    String tradeId;
    String portfolioId;
    String symbol;
    String subName;
    Side side;
    Quantity quantity;
    Price price;
    Date originDateTime;
}
