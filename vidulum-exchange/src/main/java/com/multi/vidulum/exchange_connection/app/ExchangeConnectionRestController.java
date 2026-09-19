package com.multi.vidulum.exchange_connection.app;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
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
@RestController
@RequestMapping("/exchange-connection")
@AllArgsConstructor
public class ExchangeConnectionRestController {

    private final ExchangeConnectionService service;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExchangeConnectionJson connect(@Valid @RequestBody ConnectExchangeRequest request) {
        UserId userId = authenticatedUserProvider.getCurrentUserId();
        return ExchangeConnectionJson.from(service.connect(userId, request.toCommand()));
    }

    /**
     * One connection's current state.
     *
     * <p>Answers {@code 404} for another user's connection rather than {@code 403}: a
     * {@code 403} would confirm that the id exists.
     */
    @GetMapping("/{connectionId}")
    public ExchangeConnectionJson get(@PathVariable String connectionId) {
        UserId userId = authenticatedUserProvider.getCurrentUserId();
        return ExchangeConnectionJson.from(
                service.get(userId, ExchangeConnectionId.of(connectionId)));
    }

    /** Every connection the caller owns, whatever its status. */
    @GetMapping
    public ExchangeConnectionsListJson list() {
        UserId userId = authenticatedUserProvider.getCurrentUserId();
        return new ExchangeConnectionsListJson(
                service.list(userId).stream().map(ExchangeConnectionJson::from).toList(),
                service.supportedExchanges());
    }

    @PostMapping("/{connectionId}/reconnect")
    public ExchangeConnectionJson reconnect(@PathVariable String connectionId) {
        UserId userId = authenticatedUserProvider.getCurrentUserId();
        return ExchangeConnectionJson.from(
                service.reconnect(userId, ExchangeConnectionId.of(connectionId)));
    }
}
