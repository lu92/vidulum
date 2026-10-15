package com.multi.vidulum.portfolio.api.view;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;

import java.util.List;

/**
 * Everything one owner holds, added up and valued in one currency.
 *
 * <p>Read by pnl when it takes a snapshot: it needs the totals, and the list of portfolios so it
 * can ask about each one separately. Deliberately without {@code segmentedAssets} — the holdings
 * broken down by segment are for the owner's dashboard, and pnl re-reads each portfolio anyway.
 */
public record AggregatedPortfolioView(
        UserId owner,
        List<PortfolioId> portfolioIds,
        Money currentValue,
        Money netContributions,
        Money totalUnrealisedProfit,
        Double pctUnrealisedProfit) {

    public AggregatedPortfolioView {
        portfolioIds = portfolioIds == null ? List.of() : List.copyOf(portfolioIds);
    }
}
