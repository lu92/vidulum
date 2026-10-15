package com.multi.vidulum.portfolio.api.view;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;

import java.util.List;
import java.util.Optional;

/**
 * A portfolio as another module sees it: who owns it, what it is worth, and what it holds.
 *
 * <p>Nine fields where {@code PortfolioDto.PortfolioSummaryJson} has twenty-one. The twelve left
 * out — valuation and contribution statuses, coverage ratios, the list of unpriced assets,
 * realised and wealth-change figures — exist so that {@code GET /portfolio} can tell its owner how
 * much of the answer is trustworthy. Nobody else has ever read them, and a contract that carried
 * them would make every change to that screen a change to trading's compile unit.
 *
 * <p>Identifiers are the value objects rather than strings, which is what the Json shape had to
 * use. Consumers were parsing them straight back — {@code PortfolioId.of(portfolio.getPortfolioId())}
 * appeared in three of them.
 *
 * @param pctUnrealisedProfit null when there is no cost to compare against
 */
public record PortfolioView(
        PortfolioId portfolioId,
        UserId owner,
        String name,
        Broker broker,
        Money currentValue,
        Money netContributions,
        Money unrealisedProfit,
        Double pctUnrealisedProfit,
        List<PositionView> positions) {

    public PortfolioView {
        positions = positions == null ? List.of() : List.copyOf(positions);
    }

    /**
     * The first position in this ticker, if the portfolio holds any.
     *
     * <p>"First" is the honest word: a ticker held as several positions has several, and this
     * answers with one of them. That is what the balance check in front of an order has always
     * done; stating it here keeps the next caller from assuming otherwise.
     */
    public Optional<PositionView> position(Ticker ticker) {
        return positions.stream().filter(position -> position.ticker().equals(ticker)).findFirst();
    }
}
