package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Value;

import java.time.ZonedDateTime;
import java.util.List;

@Value
//@Builder
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
        List<PnlTradeDetailsSnapshot> executedTrades;
        ZonedDateTime dateTime;
    }

    @Value
    public static class PnlTradeDetailsSnapshot {
        TradeId originTradeId;
        PortfolioId portfolioId;
        Symbol symbol;
        SubName subName;
        Side side;
        Quantity quantity;
        Money price;
        ZonedDateTime originDateTime;
    }
}
