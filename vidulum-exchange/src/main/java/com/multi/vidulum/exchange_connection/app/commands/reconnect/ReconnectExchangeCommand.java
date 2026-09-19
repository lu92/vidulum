package com.multi.vidulum.exchange_connection.app.commands.reconnect;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Brings a revoked connection back, keeping the portfolio it already feeds — so the
 * synchronisation that follows is computed against a non-empty known state.
 */
public record ReconnectExchangeCommand(
        UserId userId,
        ExchangeConnectionId connectionId) implements Command {
}
