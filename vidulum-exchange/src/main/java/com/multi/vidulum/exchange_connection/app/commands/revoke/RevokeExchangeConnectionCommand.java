package com.multi.vidulum.exchange_connection.app.commands.revoke;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Contact with the exchange was lost — the key expired, the user disconnected, the exchange
 * answered 401.
 *
 * <p>Until this existed, {@code REVOKED} had no producer, so a connection could never leave
 * {@code ACTIVE} and {@code reconnect} could never succeed: the endpoint was correct, tested and
 * unreachable.
 *
 * @param reason what to show the user later; a status without one is opaque
 */
public record RevokeExchangeConnectionCommand(
        UserId userId,
        ExchangeConnectionId connectionId,
        String reason) implements Command {
}
