package com.multi.vidulum.portfolio_spec.app.commands.confirm;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

/**
 * Turns a settled specification into a portfolio.
 *
 * @param freshSnapshot what the exchange says <b>now</b>. It travels in the command because the
 *                      backend holds no credentials and cannot fetch it — which makes the check
 *                      one of consistency, not authenticity (§4.8).
 * @param dateTime      when this is being applied. Carried here rather than read from a
 *                      {@code Clock} inside the handler, because confirmation writes three dated
 *                      facts — the opening contribution (C12), {@code markApplied} and
 *                      {@code markStale} — and they must all carry the <b>same</b> moment. A clock
 *                      consulted per use cannot promise that; a value can.
 */
public record ConfirmPortfolioSpecCommand(
        UserId userId,
        PortfolioSpecId specId,
        String portfolioName,
        Currency denominationCurrency,
        ExchangeSnapshot freshSnapshot,
        ZonedDateTime dateTime) implements Command {
}
