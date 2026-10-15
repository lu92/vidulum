package com.multi.vidulum.exchange_connection.app;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionApi;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.api.view.ConnectionView;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommand;
import com.multi.vidulum.exchange_connection.app.commands.reconnect.ReconnectExchangeCommand;
import com.multi.vidulum.exchange_connection.app.commands.sync.RecordPortfolioSyncCommand;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionQuery;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

/**
 * The exchange module answering its own contract.
 *
 * <p>Package-private and through the gateways, as with the portfolio and trading contracts. The
 * predecessor was no class at all: {@code portfolio_spec} injected three of this module's command
 * handlers and read connections out of {@code DomainExchangeConnectionRepository} directly.
 *
 * <p>The gateways arrive through Lombok's generated constructor, eagerly. The cycle that once
 * forced {@code @Lazy} here — onboarding's three command handlers consume this contract, and a
 * gateway used to be assembled from every handler in the context — is removed at its source in
 * {@code CqrsGatewayConfiguration}, so there is nothing left for an explicit constructor to carry.
 */
@Component
@AllArgsConstructor
class ExchangeConnectionApiImpl implements ExchangeConnectionApi {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    @Override
    public ConnectionView ownedOrThrow(UserId owner, ExchangeConnectionId connectionId) {
        ExchangeConnection connection = queryGateway.send(
                new GetExchangeConnectionQuery(owner, connectionId));
        return new ConnectionView(
                connection.getId(),
                connection.getBroker(),
                connection.getDenominationCurrency(),
                connection.getPortfolioId(),
                connection.getStatus());
    }

    @Override
    public void confirm(UserId owner, ExchangeConnectionId connectionId, PortfolioId portfolioId) {
        commandGateway.send(new ConfirmExchangeConnectionCommand(owner, connectionId, portfolioId));
    }

    @Override
    public void reconnect(UserId owner, ExchangeConnectionId connectionId) {
        commandGateway.send(new ReconnectExchangeCommand(owner, connectionId));
    }

    @Override
    public void recordSync(UserId owner, ExchangeConnectionId connectionId, ZonedDateTime syncedAt) {
        commandGateway.send(new RecordPortfolioSyncCommand(owner, connectionId, syncedAt));
    }
}
