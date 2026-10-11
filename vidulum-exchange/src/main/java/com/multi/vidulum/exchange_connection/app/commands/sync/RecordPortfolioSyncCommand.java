package com.multi.vidulum.exchange_connection.app.commands.sync;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

/**
 * The portfolio behind this connection now agrees with the exchange (task H1).
 *
 * <p>A command rather than a reach into the repository, for the reason the confirmation step
 * already follows: the module that owns the connection decides what a synchronisation does to it.
 *
 * @param syncedAt the moment the snapshot describes — carried, not read from a clock here, so the
 *                 anchor matches the state that was applied rather than the instant of writing
 */
public record RecordPortfolioSyncCommand(
        UserId userId,
        ExchangeConnectionId connectionId,
        ZonedDateTime syncedAt) implements Command {
}
