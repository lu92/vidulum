package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PnlPortfolioStatement {
    private PortfolioId portfolioId;
    private Money investedBalance;
    private Money currentValue;
    private Money totalProfit;
    private double pctProfit;
    private List<PnlTradeDetails> executedTrades;
}
