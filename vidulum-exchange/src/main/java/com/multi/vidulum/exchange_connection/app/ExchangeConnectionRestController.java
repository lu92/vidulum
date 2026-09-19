package com.multi.vidulum.exchange_connection.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.app.commands.connect.ConnectExchangeCommand;
import com.multi.vidulum.exchange_connection.app.commands.reconnect.ReconnectExchangeCommand;
import com.multi.vidulum.exchange_connection.app.commands.revoke.RevokeExchangeConnectionCommand;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionQuery;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionsOfUserQuery;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionsOfUserQueryHandler;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connections belong to the authenticated user; the user id is never taken from the request, so
 * one account cannot be registered on someone else's behalf.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/exchange-connection")
public class ExchangeConnectionRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExchangeConnectionDto.ExchangeConnectionJson connect(
            @Valid @RequestBody ExchangeConnectionDto.ConnectExchangeJson request) {

        ExchangeConnection connection = commandGateway.send(
                new ConnectExchangeCommand(
                        currentUser(),
                        Broker.of(request.broker()),
                        request.accountUid(),
                        request.environment(),
                        request.region(),
                        request.reportedKeyPermissions(),
                        Currency.of(request.denominationCurrency()),
                        request.credentialsMode()
                )
        );

        return ExchangeConnectionDto.ExchangeConnectionJson.from(connection);
    }

    /**
     * The exchange is no longer reachable with this key. Nothing is deleted: the connection keeps
     * its account id and its portfolio so {@link #reconnect} can pick it back up.
     */
    @PostMapping("/{connectionId}/revoke")
    public ExchangeConnectionDto.ExchangeConnectionJson revoke(
            @PathVariable String connectionId,
            @Valid @RequestBody ExchangeConnectionDto.RevokeConnectionJson request) {

        ExchangeConnection connection = commandGateway.send(new RevokeExchangeConnectionCommand(
                currentUser(), ExchangeConnectionId.of(connectionId), request.reason()));

        return ExchangeConnectionDto.ExchangeConnectionJson.from(connection);
    }

    @PostMapping("/{connectionId}/reconnect")
    public ExchangeConnectionDto.ExchangeConnectionJson reconnect(@PathVariable String connectionId) {
        ExchangeConnection connection = commandGateway.send(
                new ReconnectExchangeCommand(currentUser(), ExchangeConnectionId.of(connectionId))
        );

        return ExchangeConnectionDto.ExchangeConnectionJson.from(connection);
    }

    /**
     * One connection's current state. Answers {@code 404} for another user's connection rather
     * than {@code 403}, which would confirm that the id exists.
     */
    @GetMapping("/{connectionId}")
    public ExchangeConnectionDto.ExchangeConnectionJson get(@PathVariable String connectionId) {
        ExchangeConnection connection = queryGateway.send(
                new GetExchangeConnectionQuery(currentUser(), ExchangeConnectionId.of(connectionId))
        );

        return ExchangeConnectionDto.ExchangeConnectionJson.from(connection);
    }

    /** Every connection the caller owns, whatever its status. */
    @GetMapping
    public ExchangeConnectionDto.ExchangeConnectionsListJson list() {
        GetExchangeConnectionsOfUserQueryHandler.Result result = queryGateway.send(
                new GetExchangeConnectionsOfUserQuery(currentUser())
        );

        return ExchangeConnectionDto.ExchangeConnectionsListJson.from(result);
    }

    private UserId currentUser() {
        return authenticatedUserProvider.getCurrentUserId();
    }
}
