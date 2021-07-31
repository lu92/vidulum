package com.multi.vidulum.pnl.app.commands;

import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.pnl.domain.PnlStatement;
import com.multi.vidulum.pnl.domain.PnlTradeDetails;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.TradingRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.user.app.UserNotFoundException;
import com.multi.vidulum.user.domain.PortfolioRestClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

import static java.util.stream.Collectors.toList;

@Slf4j
@Component
@AllArgsConstructor
public class MakePnlSnapshotCommandHandler implements CommandHandler<MakePnlSnapshotCommand, PnlHistory> {

    private final PortfolioRestClient portfolioRestClient;
    private final TradingRestClient tradingRestClient;
    private final DomainPnlRepository pnlRepository;
    private final Clock clock;

    @Override
    public PnlHistory handle(MakePnlSnapshotCommand command) {
        PnlHistory pnlHistory = pnlRepository.findByUser(command.getUserId())
                .orElseThrow(() -> new UserNotFoundException(command.getUserId()));

        PnlStatement pnlStatement = createProfitAndLossStatement(command.getUserId(), command.getDateTimeRange());
        pnlHistory.getPnlStatements().add(pnlStatement);
        return pnlRepository.save(pnlHistory);
    }

    private PnlStatement createProfitAndLossStatement(UserId userId, Range<ZonedDateTime> dateTimeRange) {
        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio = portfolioRestClient.getAggregatedPortfolio(userId);
        List<TradingDto.TradeSummaryJson> executedTrades = tradingRestClient.getTradesInDateRange(userId, dateTimeRange);
        List<PnlTradeDetails> executedTradeSnapshots = mapToSnapshots(executedTrades);
        return PnlStatement.builder()
                .investedBalance(aggregatedPortfolio.getInvestedBalance())
                .currentValue(aggregatedPortfolio.getCurrentValue())
                .totalProfit(aggregatedPortfolio.getTotalProfit())
                .pctProfit(aggregatedPortfolio.getPctProfit())
                .executedTrades(executedTradeSnapshots)
                .dateTime(ZonedDateTime.now(clock))
                .build();
    }

    private List<PnlTradeDetails> mapToSnapshots(List<TradingDto.TradeSummaryJson> executedTrades) {
        return executedTrades.stream()
                .map(tradeSummaryJson -> PnlTradeDetails.builder()
                        .originTradeId(TradeId.of(tradeSummaryJson.getOriginTradeId()))
                        .portfolioId(PortfolioId.of(tradeSummaryJson.getPortfolioId()))
                        .symbol(Symbol.of(tradeSummaryJson.getSymbol()))
                        .subName(SubName.of(tradeSummaryJson.getSubName()))
                        .side(tradeSummaryJson.getSide())
                        .quantity(tradeSummaryJson.getQuantity())
                        .price(tradeSummaryJson.getPrice())
                        .originDateTime(tradeSummaryJson.getOriginDateTime())
                        .build())
                .collect(toList());
    }
}
