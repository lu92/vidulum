package com.multi.vidulum.portfolio_spec.app.commands.confirm;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Turns a settled specification into a portfolio.
 *
 * @param freshSnapshot what the exchange says <b>now</b>. It travels in the command because the
 *                      backend holds no credentials and cannot fetch it — which makes the check
 *                      one of consistency, not authenticity (§4.8).
 */
public record ConfirmPortfolioSpecCommand(
        UserId userId,
        PortfolioSpecId specId,
        String portfolioName,
        Currency denominationCurrency,
        ExchangeSnapshot freshSnapshot) implements Command {
}
