package com.multi.vidulum.portfolio.api;

import com.multi.vidulum.shared.module.ModuleApi;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.api.error.AssetNotFoundException;
import com.multi.vidulum.portfolio.api.error.NotSufficientBalance;
import com.multi.vidulum.portfolio.api.error.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.api.view.AggregatedPortfolioView;
import com.multi.vidulum.portfolio.api.view.PortfolioView;

/**
 * What other modules may ask of a portfolio.
 *
 * <p>Six methods, which is what trading, pnl, risk and the user domain between them actually call.
 * Everything else a portfolio can do — depositing, withdrawing, stating a cost, applying a trade,
 * closing — arrives either over HTTP from its owner or as an event, and neither needs a seat here.
 *
 * <p>Previously {@code portfolio.domain.portfolio.PortfolioRestClient}, whose only implementation
 * lived in {@code vidulum-app} and injected five of this module's command and query handlers
 * directly. That made the handlers a de facto public API and meant trading, pnl and risk could not
 * be exercised without the whole application. The name lied twice over: no HTTP was involved, and
 * it was not a client of anything.
 */
public interface PortfolioModuleApi extends ModuleApi {

    /** @return the id of the portfolio that now exists */
    PortfolioId open(String name, UserId owner, Broker broker, Currency allowedDepositCurrency);

    /**
     * Reserves units against one of our own orders.
     *
     * @throws AssetNotFoundException when the portfolio holds no such asset
     * @throws NotSufficientBalance when it holds too little of it
     */
    void lock(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity);

    void unlock(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity);

    /**
     * The portfolio valued in <b>its own</b> currency — what an order's balance check wants.
     *
     * @throws PortfolioNotFoundException also for a portfolio belonging to somebody else
     */
    PortfolioView get(PortfolioId portfolioId);

    /**
     * The portfolio valued in the currency asked for (task C17).
     *
     * <p>Separate from {@link #get(PortfolioId)} because recording history needs one currency
     * across every portfolio in a statement, and the portfolios need not agree on one.
     */
    PortfolioView get(PortfolioId portfolioId, Currency valuedIn);

    /** Everything the owner holds, valued in the currency asked for (task B4). */
    AggregatedPortfolioView aggregate(UserId owner, Currency valuedIn);
}
