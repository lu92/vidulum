package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.OriginTradeId;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.StoredTrade;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.trading.app.commands.MakeTradeCommand;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;

@RestController
@AllArgsConstructor
public class TradingRestController {
    private final CommandGateway commandGateway;

    @PostMapping("/trading")
    public void makeTrade(@RequestBody TradingDto.TradeExecutedJson tradeExecutedJson) {
        MakeTradeCommand command = MakeTradeCommand.builder()
                .userId(UserId.of(tradeExecutedJson.getUserId()))
                .portfolioId(PortfolioId.of(tradeExecutedJson.getPortfolioId()))
                .originTradeId(OriginTradeId.of(tradeExecutedJson.getOriginTradeId()))
                .symbol(Symbol.of(tradeExecutedJson.getSymbol()))
                .side(tradeExecutedJson.getSide())
                .quantity(tradeExecutedJson.getQuantity())
                .price(tradeExecutedJson.getPrice())
                .dateTime(ZonedDateTime.now())
                .build();

        commandGateway.send(command);
    }

}
