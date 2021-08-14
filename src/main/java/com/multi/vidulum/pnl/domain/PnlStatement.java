package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;

@Data
@Builder
public class PnlStatement {
    private Money investedBalance;
    private Money currentValue;
    private Money totalProfit;
    private double pctProfit;
    private List<PnlTradeDetails> executedTrades;
    private ZonedDateTime dateTime;
}
