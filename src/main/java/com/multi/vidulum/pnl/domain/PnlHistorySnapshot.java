package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;
import java.util.List;

@Value
public class PnlHistorySnapshot implements EntitySnapshot<PnlId> {

    PnlId pnlId;
    UserId userId;
    List<PnlStatementSnapshot> pnlStatements;

    @Override
    public PnlId id() {
        return pnlId;
    }

    @Value
    public static class PnlStatementSnapshot {
        Money investedBalance;
        Money currentValue;
        Money totalProfit;
        double pctProfit;
        List<PnlPortfolioStatementSnapshot> portfolioStatements;
        ZonedDateTime dateTime;
    }

    @Value
    @Builder
    public static class PnlPortfolioStatementSnapshot {
        PortfolioId portfolioId;
        Money investedBalance;
        Money currentValue;
        Money totalProfit;
        double pctProfit;
        List<PnlTradeDetailsSnapshot> executedTrades;
    }

    @Value
    @Builder
    public static class PnlTradeDetailsSnapshot {
        OriginTradeId originTradeId;
        TradeId tradeId;
        PortfolioId portfolioId;
        Symbol symbol;
        SubName subName;
        Side side;
        Quantity quantity;
        Money price;
        ZonedDateTime originDateTime;
    }
}
