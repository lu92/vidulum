package com.multi.vidulum.portfolio.app.commands.withdraw;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Builder
public class WithdrawMoneyCommand implements Command {
    private final PortfolioId portfolioId;
    private final Money money;

    /** Identity of the ledger entry this creates — minted outside, so a test can pin it. */
    private final ContributionId contributionId;

    /** When this happened. See {@code DepositMoneyCommand#when} for why the command carries it. */
    private final ZonedDateTime dateTime;
}
