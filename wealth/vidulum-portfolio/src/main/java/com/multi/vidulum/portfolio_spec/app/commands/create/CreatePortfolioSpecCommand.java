package com.multi.vidulum.portfolio_spec.app.commands.create;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Computes what has to be decided before a portfolio can be written.
 *
 * @param portfolioId          the portfolio whose contents are the known state; {@code null}
 *                             during onboarding, which is simply the case where nothing is known
 * @param denominationCurrency what the portfolio is valued in. Required up front rather than at
 *                             confirmation, because it decides which snapshot line is cash and
 *                             therefore what the user is asked about (C10).
 */
public record CreatePortfolioSpecCommand(
        UserId userId,
        String connectionId,
        PortfolioId portfolioId,
        Currency denominationCurrency,
        ExchangeSnapshot snapshot) implements Command {
}
