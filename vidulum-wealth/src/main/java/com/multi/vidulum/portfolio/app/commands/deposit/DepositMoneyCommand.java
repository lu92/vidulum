package com.multi.vidulum.portfolio.app.commands.deposit;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Builder
public class DepositMoneyCommand implements Command {
    private final PortfolioId portfolioId;
    private final Money money;

    /** Identity of the ledger entry this creates — minted outside, so a test can pin it. */
    private final ContributionId contributionId;

    /**
     * When this happened, carried by the command rather than read from a {@code Clock} inside the
     * handler.
     *
     * <p>The command then states the whole fact — who, how much, and as of when — and reading it
     * tells you everything that will be written. A handler holding a clock hides half of that: the
     * same command produces a different ledger entry depending on when it is executed, which is
     * also why every test that wanted a fixed moment had to inject a clock to get one.
     */
    private final ZonedDateTime dateTime;
}
