package com.multi.vidulum.pnl.app.commands;

import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.domain.*;
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
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;
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

        Map<String, List<TradingDto.TradeSummaryJson>> groupedTradesByPortfolio =
                tradingRestClient.getTradesInDateRange(userId, dateTimeRange).stream()
                        .collect(groupingBy(TradingDto.TradeSummaryJson::getPortfolioId));

        List<PnlPortfolioStatement> pnlPortfolioStatements = aggregatedPortfolio.getPortfolioIds().stream()
                .map(portfolioId -> {

                    // get portfolio stats
                    PortfolioDto.PortfolioSummaryJson portfolioSummaryJson = portfolioRestClient.getPortfolio(PortfolioId.of(portfolioId));

                    // calculate trades from given period of time
                    List<PnlTradeDetails> tradeDetails = mapToSnapshots(groupedTradesByPortfolio.getOrDefault(portfolioId, List.of()));

                    return PnlPortfolioStatement.builder()
                            .portfolioId(PortfolioId.of(portfolioSummaryJson.getPortfolioId()))
                            .investedBalance(portfolioSummaryJson.getInvestedBalance())
                            .currentValue(portfolioSummaryJson.getCurrentValue())
                            .totalProfit(portfolioSummaryJson.getProfit())
                            .pctProfit(portfolioSummaryJson.getPctProfit())
                            .executedTrades(tradeDetails)
                            .build();
                })
                .collect(toList());

        return PnlStatement.builder()
                .investedBalance(aggregatedPortfolio.getInvestedBalance())
                .currentValue(aggregatedPortfolio.getCurrentValue())
                .totalProfit(aggregatedPortfolio.getTotalProfit())
                .pctProfit(aggregatedPortfolio.getPctProfit())
                .pnlPortfolioStatements(pnlPortfolioStatements)
                .dateTime(ZonedDateTime.now(clock))
                .build();
    }

    private List<PnlTradeDetails> mapToSnapshots(List<TradingDto.TradeSummaryJson> executedTrades) {
        return executedTrades.stream()
                .map(tradeSummaryJson -> PnlTradeDetails.builder()
                        .originTradeId(OriginTradeId.of(tradeSummaryJson.getOriginTradeId()))
                        .tradeId(TradeId.of(tradeSummaryJson.getTradeId()))
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
