package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.deposit.DepositMoneyCommand;
import com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.withdraw.WithdrawMoneyCommand;
import com.multi.vidulum.portfolio.app.queries.*;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.trading.domain.OpenedPositions;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
public class PortfolioRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final PortfolioSummaryMapper portfolioSummaryMapper;
    private final PositionMapper positionMapper;
    private final PortfolioAccess access;

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
                .build();
        commandGateway.send(command);
    }

    @PostMapping("/portfolio/withdraw")
    public void withdrawMoney(@RequestBody PortfolioDto.WithdrawMoneyJson request) {
        WithdrawMoneyCommand command = WithdrawMoneyCommand.builder()
                .portfolioId(access.requireOwned(request.getPortfolioId()))
                .money(request.getMoney())
                .build();
        commandGateway.send(command);
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
