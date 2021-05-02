package com.multi.vidulum.portfolio.app.commands.withdraw;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WithdrawMoneyCommand implements Command {
    private final PortfolioId portfolioId;
    private final Money money;
}
