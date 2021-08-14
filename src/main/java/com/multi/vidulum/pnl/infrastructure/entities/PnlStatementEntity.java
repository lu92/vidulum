package com.multi.vidulum.pnl.infrastructure.entities;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Value;

import java.util.Date;
import java.util.List;

@Value
@Builder
public class PnlStatementEntity {
    Money investedBalance;
    Money currentValue;
    Money totalProfit;
    double pctProfit;
    List<PnlTradeDetailsEntity> executedTrades;
    Date dateTime;
}
