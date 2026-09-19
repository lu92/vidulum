package com.multi.vidulum.portfolio_spec.app.commands.create;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Computes what has to be decided before a portfolio can be written.
 *
 * @param portfolioId the portfolio whose contents are the known state; {@code null} during
 *                    onboarding, which is simply the case where nothing is known yet
 */
public record CreatePortfolioSpecCommand(
        UserId userId,
        String connectionId,
        PortfolioId portfolioId,
        ExchangeSnapshot snapshot) implements Command {
}
