package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.deposit.DepositMoneyCommand;
import com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.price.StateCostOfPositionCommand;
import com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.withdraw.WithdrawMoneyCommand;
import com.multi.vidulum.portfolio.app.queries.*;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionId;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.trading.domain.OpenedPositions;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.ZonedDateTime;

@RestController
@AllArgsConstructor
public class PortfolioRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final PortfolioSummaryMapper portfolioSummaryMapper;
    private final PositionMapper positionMapper;
    private final PortfolioAccess access;

    /**
     * Where the clock lives now. The deposit and withdrawal handlers used to hold one and stamp the
     * ledger entry themselves; keeping it at the edge leaves them pure functions of their command.
     */
    private final Clock clock;

    @PostMapping("/portfolio")
    public PortfolioDto.PortfolioSummaryJson createEmptyPortfolio(@RequestBody PortfolioDto.CreateEmptyPortfolioJson request) {
        CreateEmptyPortfolioCommand command = CreateEmptyPortfolioCommand.builder()
                .portfolioId(PortfolioId.generate())
                .name(request.getName())
                // From the token. Taking it from the body let a caller open a portfolio in
                // someone else's name, and every later check would have agreed with the lie.
                .userId(access.currentUser())
                .broker(Broker.of(request.getBroker()))
                .allowedDepositCurrency(Currency.of(request.getAllowedDepositCurrency()))
                .build();

        Portfolio portfolio = commandGateway.send(command);
        return portfolioSummaryMapper.map(portfolio, portfolio.getAllowedDepositCurrency());
    }

    @PostMapping("/portfolio/deposit")
    public void depositMoney(@RequestBody PortfolioDto.DepositMoneyJson request) {
        DepositMoneyCommand command = DepositMoneyCommand.builder()
                .portfolioId(access.requireOwned(request.getPortfolioId()))
                .money(request.getMoney())
                // Identity and moment are minted here rather than inside the handler: the command
                // then states the whole fact, and the same command replayed writes the same entry.
                .contributionId(ContributionId.generate())
                .dateTime(ZonedDateTime.now(clock))
                .build();
        commandGateway.send(command);
    }

    @PostMapping("/portfolio/withdraw")
    public void withdrawMoney(@RequestBody PortfolioDto.WithdrawMoneyJson request) {
        WithdrawMoneyCommand command = WithdrawMoneyCommand.builder()
                .portfolioId(access.requireOwned(request.getPortfolioId()))
                .money(request.getMoney())
                .contributionId(ContributionId.generate())
                .dateTime(ZonedDateTime.now(clock))
                .build();
        commandGateway.send(command);
    }

    /**
     * The owner says what a position cost them (task C8).
     *
     * <p>Until now this could only be answered during onboarding, while a specification was open.
     * A holding that arrived afterwards — a transfer in, an airdrop — had no route at all, so its
     * result stayed withheld forever and selling it settled nothing computable.
     */
    @PostMapping("/portfolio/asset/cost")
    public PortfolioDto.PortfolioSummaryJson stateCostOfPosition(
            @RequestBody PortfolioDto.StateCostJson request) {

        Portfolio portfolio = commandGateway.send(StateCostOfPositionCommand.builder()
                .portfolioId(access.requireOwned(request.getPortfolioId()))
                .ticker(Ticker.of(request.getTicker()))
                .subName(SubName.of(request.getSubName()))
                .avgPrice(request.getAvgPrice())
                .dateTime(ZonedDateTime.now(clock))
                .build());

        return portfolioSummaryMapper.map(portfolio, portfolio.getAllowedDepositCurrency());
    }

    @PostMapping("/portfolio/asset/lock")
    public void lockAsset(@RequestBody PortfolioDto.LockAssetJson request) {
        LockAssetCommand command = LockAssetCommand.builder()
                .portfolioId(access.requireOwned(request.getPortfolioId()))
                .ticker(Ticker.of(request.getTicker()))
                .orderId(OrderId.of(request.getOrderId()))
                .quantity(request.getQuantity())
                .build();
        commandGateway.send(command);
    }

    @PostMapping("/portfolio/asset/unlock")
    public void unlockAsset(@RequestBody PortfolioDto.UnlockAssetJson request) {
        UnlockAssetCommand command = UnlockAssetCommand.builder()
                .portfolioId(access.requireOwned(request.getPortfolioId()))
                .ticker(Ticker.of(request.getTicker()))
                .orderId(OrderId.of(request.getOrderId()))
                .quantity(request.getQuantity())
                .build();
        commandGateway.send(command);
    }

    @GetMapping("/portfolio/{id}/{currency}")
    public PortfolioDto.PortfolioSummaryJson getPortfolio(@PathVariable("id") String id, @PathVariable("currency") String currency) {
        GetPortfolioQuery query = GetPortfolioQuery.builder()
                .portfolioId(access.requireOwned(id))
                .build();

        Portfolio portfolio = queryGateway.send(query);
        return portfolioSummaryMapper.map(portfolio, Currency.of(currency));
    }

    /** The caller's own holdings. The user id used to come from the path, so anyone could ask. */
    @GetMapping("/aggregated-portfolio/{currency}")
    public PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(@PathVariable String currency) {
        GetAggregatedPortfolioQuery query = GetAggregatedPortfolioQuery.builder()
                .userId(access.currentUser())
                .build();
        AggregatedPortfolio aggregatedPortfolio = queryGateway.send(query);
        return portfolioSummaryMapper.map(aggregatedPortfolio, Currency.of(currency));
    }

    @GetMapping("/portfolio/opened-positions/{portfolioId}")
    public PortfolioDto.OpenedPositionsJson getOpenedPositions(@PathVariable("portfolioId") String portfolioId) {
        GetPositionViewOfPortfolioQuery query = GetPositionViewOfPortfolioQuery.builder()
                .portfolioId(access.requireOwned(portfolioId))
                .build();
        OpenedPositions openedPositions = queryGateway.send(query);
        return positionMapper.map(openedPositions);
    }
}
