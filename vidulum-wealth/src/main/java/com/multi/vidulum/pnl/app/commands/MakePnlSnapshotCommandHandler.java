package com.multi.vidulum.pnl.app.commands;

import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.domain.*;
import com.multi.vidulum.portfolio.api.view.AggregatedPortfolioView;
import com.multi.vidulum.portfolio.api.PortfolioModuleApi;
import com.multi.vidulum.portfolio.api.view.PortfolioView;
import com.multi.vidulum.portfolio.domain.TradingRestClient;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.common.UserNotFoundException;
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

    private final PortfolioModuleApi portfolioApi;
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

        AggregatedPortfolioView aggregatedPortfolio =
                portfolioApi.aggregate(userId, valuationCurrency);

        // Recorded beside the figures, not derived from them later: what a currency was worth last
        // Tuesday cannot be looked up on Friday, so a rate not written down now is lost. Absent
        // when none is held - a valuation without its rate is still the valuation, and refusing to
        // record one would leave a permanent hole instead of an incomplete entry.
        ValuationBasis basis = valuationRates.basisFor(valuationCurrency)
                .orElseGet(() -> ValuationBasis.pivot(valuationCurrency));

        Map<String, List<TradingDto.TradeSummaryJson>> groupedTradesByPortfolio =
                tradingRestClient.getTradesInDateRange(userId, dateTimeRange).stream()
                        .collect(groupingBy(TradingDto.TradeSummaryJson::getPortfolioId));

        List<PnlPortfolioStatement> pnlPortfolioStatements = aggregatedPortfolio.portfolioIds().stream()
                .map(portfolioId -> {

                    // get portfolio stats
                    // In the statement's currency, not each portfolio's own. They used to be read
                    // in whatever currency each portfolio settled in while the total beside them
                    // was in dollars, so one statement mixed currencies and nothing said so.
                    PortfolioView portfolio = portfolioApi.get(portfolioId, valuationCurrency);

                    // calculate trades from given period of time
                    List<PnlTradeDetails> tradeDetails = mapToSnapshots(groupedTradesByPortfolio.getOrDefault(portfolioId.getId(), List.of()));

                    return PnlPortfolioStatement.builder()
                            .portfolioId(portfolio.portfolioId())
                            .netContributions(portfolio.netContributions())
                            .currentValue(portfolio.currentValue())
                            .totalUnrealisedProfit(portfolio.unrealisedProfit())
                            .pctUnrealisedProfit(portfolio.pctUnrealisedProfit())
                            .executedTrades(tradeDetails)
                            .build();
                })
                .collect(toList());

        return PnlStatement.builder()
                .basis(basis)
                .netContributions(aggregatedPortfolio.netContributions())
                .currentValue(aggregatedPortfolio.currentValue())
                .totalUnrealisedProfit(aggregatedPortfolio.totalUnrealisedProfit())
                .pctUnrealisedProfit(aggregatedPortfolio.pctUnrealisedProfit())
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
