package com.multi.vidulum.portfolio.app.commands.close;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClosePortfolioCommand implements Command {
    private final PortfolioId portfolioId;
}
