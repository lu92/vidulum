package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionApi;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.api.view.ConnectionView;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommand;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommandHandler;
import com.multi.vidulum.exchange_connection.app.commands.reconnect.ReconnectExchangeCommand;
import com.multi.vidulum.exchange_connection.app.commands.reconnect.ReconnectExchangeCommandHandler;
import com.multi.vidulum.exchange_connection.app.commands.sync.RecordPortfolioSyncCommand;
import com.multi.vidulum.exchange_connection.app.commands.sync.RecordPortfolioSyncCommandHandler;
import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * The exchange contract answered by the <b>real</b> exchange handlers over an in-memory store.
 *
 * <p>Not a fake of the contract: a connection genuinely transitions, because that is what these
 * tests are about — onboarding is supposed to leave the connection in service and a resumption is
 * supposed to move its synchronisation anchor. Only Mongo is replaced.
 *
 * <p>It exists because {@code portfolio_spec} used to receive these handlers and the repository
 * one by one. The production handlers now take a single {@code ExchangeConnectionApi}, so the
 * wiring that used to be spread across each test's constructor call lives here once.
 */
class ExchangeConnectionApiForSpec implements ExchangeConnectionApi {

    private final DomainExchangeConnectionRepository connections;
    private final ConfirmExchangeConnectionCommandHandler confirmHandler;
    private final ReconnectExchangeCommandHandler reconnectHandler;
    private final RecordPortfolioSyncCommandHandler recordSyncHandler;

    ExchangeConnectionApiForSpec(DomainExchangeConnectionRepository connections, Clock clock) {
        this.connections = connections;
        this.confirmHandler = new ConfirmExchangeConnectionCommandHandler(connections, clock);
        this.reconnectHandler = new ReconnectExchangeCommandHandler(connections, clock);
        this.recordSyncHandler = new RecordPortfolioSyncCommandHandler(connections);
    }

    @Override
    public ConnectionView ownedOrThrow(UserId owner, ExchangeConnectionId connectionId) {
        ExchangeConnection connection = connections.findOwnedOrThrow(owner, connectionId);
        return new ConnectionView(
                connection.getId(),
                connection.getBroker(),
                connection.getDenominationCurrency(),
                connection.getPortfolioId(),
                connection.getStatus());
    }

    @Override
    public void confirm(UserId owner, ExchangeConnectionId connectionId, PortfolioId portfolioId) {
        confirmHandler.handle(new ConfirmExchangeConnectionCommand(owner, connectionId, portfolioId));
    }

    @Override
    public void reconnect(UserId owner, ExchangeConnectionId connectionId) {
        reconnectHandler.handle(new ReconnectExchangeCommand(owner, connectionId));
    }

    @Override
    public void recordSync(UserId owner, ExchangeConnectionId connectionId, ZonedDateTime syncedAt) {
        recordSyncHandler.handle(new RecordPortfolioSyncCommand(owner, connectionId, syncedAt));
    }
}
