package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.update.ApplyTradeCommand;
import com.multi.vidulum.portfolio.app.queries.GetPortfolioQuery;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
public class PortfolioRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final PortfolioSummaryMapper portfolioSummaryMapper;

    @PostMapping("/portfolio")
    public PortfolioDto.PortfolioSummaryJson createEmptyPortfolio(@RequestBody PortfolioDto.CreateEmptyPortfolioJson request) {
        CreateEmptyPortfolioCommand command = CreateEmptyPortfolioCommand.builder()
                .name(request.getName())
                .userId(UserId.of(request.getUserId()))
                .build();

        Portfolio portfolio = commandGateway.send(command);
        return portfolioSummaryMapper.map(portfolio);
    }

    @GetMapping("/portfolio/{id}")
    public PortfolioDto.PortfolioSummaryJson getPortfolio(@PathVariable("id") String id) {
        GetPortfolioQuery query = GetPortfolioQuery.builder()
                .portfolioId(PortfolioId.of(id))
                .build();

        Portfolio portfolio = queryGateway.send(query);
        return portfolioSummaryMapper.map(portfolio);
    }

    @PostMapping("/portfolio/trading")
    public void applyTrade(@RequestBody PortfolioDto.TradeExecutedJson tradeExecutedJson) {
        ApplyTradeCommand command = ApplyTradeCommand.builder()
                .tradeId(TradeId.of(tradeExecutedJson.getTradeId()))
                .portfolioId(PortfolioId.of(tradeExecutedJson.getPortfolioId()))
                .ticker(Ticker.of(tradeExecutedJson.getTicker()))
                .side(tradeExecutedJson.getSide())
                .quantity(tradeExecutedJson.getQuantity())
                .price(tradeExecutedJson.getPrice())
                .build();

        commandGateway.send(command);
    }

}
