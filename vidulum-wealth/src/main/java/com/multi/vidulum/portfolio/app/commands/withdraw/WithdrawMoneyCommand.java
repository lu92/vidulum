package com.multi.vidulum.portfolio.app.commands.withdraw;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WithdrawMoneyCommand implements Command {
    private final PortfolioId portfolioId;
    private final Money money;

    /** Identity of the ledger entry this creates — generated outside so a test can pin it. */
    private final String contributionId;
}
