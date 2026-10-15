package com.multi.vidulum.exchange_connection.api.view;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.exchange_connection.api.ConnectionStatus;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionId;

/**
 * A connection as another module sees it.
 *
 * <p>Five fields out of the sixteen on {@code ExchangeConnection}. Left out: the account uid, the
 * environment, the region, the reported key permissions, the credentials mode, the status reason
 * and four timestamps — all of them answers to the owner's own questions about a connection, read
 * by {@code GET /exchange-connection/{id}} and by nobody else.
 *
 * @param portfolioId the portfolio this connection serves, or {@code null} before onboarding
 *                    created one
 */
public record ConnectionView(
        ExchangeConnectionId id,
        Broker broker,
        Currency denominationCurrency,
        PortfolioId portfolioId,
        ConnectionStatus status) {
}
