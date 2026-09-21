package com.multi.vidulum.pnl.infrastructure.entities;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;

import java.util.List;

@Builder
@Getter
@Value
@ToString
public class PnlPortfolioStatementEntity {
    String portfolioId;
    Money investedBalance;
    Money currentValue;
    Money totalUnrealisedProfit;
    Double pctUnrealisedProfit;
    List<PnlTradeDetailsEntity> executedTrades;
}
