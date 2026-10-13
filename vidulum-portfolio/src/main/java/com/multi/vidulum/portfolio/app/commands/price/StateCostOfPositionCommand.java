package com.multi.vidulum.portfolio.app.commands.price;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * The owner says what a position cost them (task C8).
 *
 * <p>The answer to a question the system has been asking since C1 and could not, until now, be
 * given outside onboarding: a holding transferred in from elsewhere has no price anybody knows,
 * so its result is withheld (C3) and selling it settles nothing computable (C7). At a tax office
 * that difference is money — without a cost there is nothing to deduct from the proceeds.
 *
 * <p>The price is per unit and carries its own currency. It is not converted on the way in: a cost
 * stated in dollars is a fact about dollars, and F6 learned what mixing currencies at the wrong
 * moment produces.
 */
@Getter
@Builder
public class StateCostOfPositionCommand implements Command {
    private final PortfolioId portfolioId;
    private final Ticker ticker;

    /** Which position of that ticker — the split C2 introduced and C15 finally exposed. */
    private final SubName subName;

    private final Price avgPrice;

    /** When it was stated; the command carries the moment, as every command has since F7. */
    private final ZonedDateTime dateTime;
}
