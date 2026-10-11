package com.multi.vidulum.pnl.app.commands;

import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.domain.*;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.TradingRestClient;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.common.UserNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioRestClient;
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
    private final ValuationRates valuationRates;
    private final Clock clock;

    @Override
    public PnlHistory handle(MakePnlSnapshotCommand command) {
        PnlHistory pnlHistory = pnlRepository.findByUser(command.getUserId())
                .orElseThrow(() -> new UserNotFoundException(command.getUserId()));

        PnlStatement pnlStatement = createProfitAndLossStatement(
                command.getUserId(), command.getDateTimeRange(), command.getValuationCurrency());
        pnlHistory.getPnlStatements().add(pnlStatement);
        return pnlRepository.save(pnlHistory);
    }

    private PnlStatement createProfitAndLossStatement(
            UserId userId, Range<ZonedDateTime> dateTimeRange, Currency valuationCurrency) {

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio =
                portfolioRestClient.getAggregatedPortfolio(userId, valuationCurrency);

        // Recorded beside the figures, not derived from them later: what a currency was worth last
        // Tuesday cannot be looked up on Friday, so a rate not written down now is lost. Absent
        // when none is held - a valuation without its rate is still the valuation, and refusing to
        // record one would leave a permanent hole instead of an incomplete entry.
        ValuationBasis basis = valuationRates.basisFor(valuationCurrency)
                .orElseGet(() -> ValuationBasis.pivot(valuationCurrency));

        Map<String, List<TradingDto.TradeSummaryJson>> groupedTradesByPortfolio =
                tradingRestClient.getTradesInDateRange(userId, dateTimeRange).stream()
                        .collect(groupingBy(TradingDto.TradeSummaryJson::getPortfolioId));

        List<PnlPortfolioStatement> pnlPortfolioStatements = aggregatedPortfolio.getPortfolioIds().stream()
                .map(portfolioId -> {

                    // get portfolio stats
                    // In the statement's currency, not each portfolio's own. They used to be read
                    // in whatever currency each portfolio settled in while the total beside them
                    // was in dollars, so one statement mixed currencies and nothing said so.
                    PortfolioDto.PortfolioSummaryJson portfolioSummaryJson =
                            portfolioRestClient.getPortfolio(PortfolioId.of(portfolioId), valuationCurrency);

                    // calculate trades from given period of time
                    List<PnlTradeDetails> tradeDetails = mapToSnapshots(groupedTradesByPortfolio.getOrDefault(portfolioId, List.of()));

                    return PnlPortfolioStatement.builder()
                            .portfolioId(PortfolioId.of(portfolioSummaryJson.getPortfolioId()))
                            .netContributions(portfolioSummaryJson.getNetContributions())
                            .currentValue(portfolioSummaryJson.getCurrentValue())
                            .totalUnrealisedProfit(portfolioSummaryJson.getUnrealisedProfit())
                            .pctUnrealisedProfit(portfolioSummaryJson.getPctUnrealisedProfit())
                            .executedTrades(tradeDetails)
                            .build();
                })
                .collect(toList());

        return PnlStatement.builder()
                .basis(basis)
                .netContributions(aggregatedPortfolio.getNetContributions())
                .currentValue(aggregatedPortfolio.getCurrentValue())
                .totalUnrealisedProfit(aggregatedPortfolio.getTotalUnrealisedProfit())
                .pctUnrealisedProfit(aggregatedPortfolio.getPctUnrealisedProfit())
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
