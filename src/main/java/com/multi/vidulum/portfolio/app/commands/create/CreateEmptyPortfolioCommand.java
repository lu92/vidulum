package com.multi.vidulum.portfolio.app.commands.create;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CreateEmptyPortfolioCommand implements Command {
    private final PortfolioId portfolioId;
    private final String name;
    private final UserId userId;
    private final Broker broker;
    private final Currency allowedDepositCurrency;
}
