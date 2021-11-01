package com.multi.vidulum.portfolio.app.commands.lock;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LockAssetCommand implements Command {
    PortfolioId portfolioId;
    Ticker ticker;
    Quantity quantity;
}
