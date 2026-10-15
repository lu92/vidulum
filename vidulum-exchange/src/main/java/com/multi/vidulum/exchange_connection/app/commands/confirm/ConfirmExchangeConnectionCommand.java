package com.multi.vidulum.exchange_connection.app.commands.confirm;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Onboarding finished: the portfolio exists, so the connection starts serving synchronisation.
 *
 * <p>Sent from whoever created the portfolio — today the portfolio-spec confirmation in
 * {@code vidulum-portfolio}. The rule that a connection may only be confirmed once stays here,
 * with the aggregate that owns it.
 */
public record ConfirmExchangeConnectionCommand(
        UserId userId,
        ExchangeConnectionId connectionId,
        PortfolioId portfolioId) implements Command {
}
