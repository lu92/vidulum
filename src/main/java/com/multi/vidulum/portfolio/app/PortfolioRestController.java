package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.deposit.DepositMoneyCommand;
import com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.withdraw.WithdrawMoneyCommand;
import com.multi.vidulum.portfolio.app.queries.*;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
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

    @PostMapping("/portfolio")
    public PortfolioDto.PortfolioSummaryJson createEmptyPortfolio(@RequestBody PortfolioDto.CreateEmptyPortfolioJson request) {
        CreateEmptyPortfolioCommand command = CreateEmptyPortfolioCommand.builder()
                .portfolioId(PortfolioId.generate())
                .name(request.getName())
                .userId(UserId.of(request.getUserId()))
                .broker(Broker.of(request.getBroker()))
                .build();

        Portfolio portfolio = commandGateway.send(command);
        return portfolioSummaryMapper.map(portfolio, portfolio.getAllowedDepositCurrency());
    }

    @PostMapping("/portfolio/deposit")
    public void depositMoney(@RequestBody PortfolioDto.DepositMoneyJson request) {
        DepositMoneyCommand command = DepositMoneyCommand.builder()
                .portfolioId(PortfolioId.of(request.getPortfolioId()))
                .money(request.getMoney())
                .build();
        commandGateway.send(command);
    }

    @PostMapping("/portfolio/withdraw")
    public void withdrawMoney(@RequestBody PortfolioDto.WithdrawMoneyJson request) {
        WithdrawMoneyCommand command = WithdrawMoneyCommand.builder()
                .portfolioId(PortfolioId.of(request.getPortfolioId()))
                .money(request.getMoney())
                .build();
        commandGateway.send(command);
    }

    @PostMapping("/portfolio/asset/lock")
    public void lockAsset(@RequestBody PortfolioDto.LockAssetJson request) {
        LockAssetCommand command = LockAssetCommand.builder()
                .portfolioId(PortfolioId.of(request.getPortfolioId()))
                .ticker(Ticker.of(request.getTicker()))
                .quantity(request.getQuantity())
                .build();
        commandGateway.send(command);
    }

    @PostMapping("/portfolio/asset/unlock")
    public void unlockAsset(@RequestBody PortfolioDto.UnlockAssetJson request) {
        UnlockAssetCommand command = UnlockAssetCommand.builder()
                .portfolioId(PortfolioId.of(request.getPortfolioId()))
                .ticker(Ticker.of(request.getTicker()))
                .quantity(request.getQuantity())
                .build();
        commandGateway.send(command);
    }

    @GetMapping("/portfolio/{id}/{currency}")
    public PortfolioDto.PortfolioSummaryJson getPortfolio(@PathVariable("id") String id, @PathVariable("currency") String currency) {
        GetPortfolioQuery query = GetPortfolioQuery.builder()
                .portfolioId(PortfolioId.of(id))
                .build();

        Portfolio portfolio = queryGateway.send(query);
        return portfolioSummaryMapper.map(portfolio, Currency.of(currency));
    }

    @GetMapping("/aggregated-portfolio/{userId}/{currency}")
    public PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(@PathVariable("userId") String userId, @PathVariable String currency) {
        GetAggregatedPortfolioQuery query = GetAggregatedPortfolioQuery.builder()
                .userId(UserId.of(userId))
                .build();
        AggregatedPortfolio aggregatedPortfolio = queryGateway.send(query);
        return portfolioSummaryMapper.map(aggregatedPortfolio, Currency.of(currency));
    }

    @GetMapping("/portfolio/opened-positions/{portfolioId}")
    public PortfolioDto.OpenedPositionsJson getOpenedPositions(@PathVariable("portfolioId") String portfolioId) {
        GetPositionViewOfPortfolioQuery query = GetPositionViewOfPortfolioQuery.builder()
                .portfolioId(PortfolioId.of(portfolioId))
                .build();
        OpenedPositions openedPositions = queryGateway.send(query);
        return positionMapper.map(openedPositions);
    }
}
