package com.multi.vidulum.pnl.infrastructure.entities;

import com.multi.vidulum.common.Money;
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
    String portfolioId;
    String symbol;
    String subName;
    Side side;
    Quantity quantity;
    Money price;
    Date originDateTime;
}
