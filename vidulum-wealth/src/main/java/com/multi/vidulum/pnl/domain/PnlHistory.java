package com.multi.vidulum.pnl.domain;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

@Data
@Builder
public class PnlHistory implements Aggregate<PnlId, PnlHistorySnapshot> {
    PnlId pnlId;
    UserId userId;
    List<PnlStatement> pnlStatements;

    /**
     * What this portfolio was worth as of {@code at} (task F9).
     *
     * <p>The most recent snapshot taken <b>at or before</b> that moment — never a later one, which
     * would answer the question with information the date did not have. Empty when nothing was
     * recorded that early: the honest answer for a window that starts before we were watching, and
     * the reason a windowed measure withholds instead of guessing.
     */
    public Optional<PortfolioValuation> valuationAt(PortfolioId portfolioId, ZonedDateTime at) {
        return pnlStatements.stream()
                .filter(statement -> !statement.getDateTime().isAfter(at))
                .sorted(Comparator.comparing(PnlStatement::getDateTime).reversed())
                .flatMap(statement -> statement.getPnlPortfolioStatements().stream()
                        .filter(portfolio -> portfolioId.equals(portfolio.getPortfolioId()))
                        .map(portfolio -> new PortfolioValuation(
                                portfolioId,
                                statement.getDateTime(),
                                portfolio.getCurrentValue(),
                                portfolio.getNetContributions())))
                .findFirst();
    }

    @Override
    public PnlHistorySnapshot getSnapshot() {
        List<PnlHistorySnapshot.PnlStatementSnapshot> pnlStatementSnapshots = pnlStatements.stream()
                .map(pnlStatement -> {
                    List<PnlHistorySnapshot.PnlPortfolioStatementSnapshot> portfolioStatementSnapshots =
                            pnlStatement.getPnlPortfolioStatements().stream()
                                    .map(pnlPortfolioStatement -> {
                                        List<PnlHistorySnapshot.PnlTradeDetailsSnapshot> executedTradeSnapshots = pnlPortfolioStatement.getExecutedTrades().stream()
                                                .map(pnlTradeDetails ->
                                                        PnlHistorySnapshot.PnlTradeDetailsSnapshot.builder()
                                                                .originTradeId(pnlTradeDetails.getOriginTradeId())
                                                                .tradeId(pnlTradeDetails.getTradeId())
                                                                .portfolioId(pnlTradeDetails.getPortfolioId())
                                                                .symbol(pnlTradeDetails.getSymbol())
                                                                .subName(pnlTradeDetails.getSubName())
                                                                .side(pnlTradeDetails.getSide())
                                                                .quantity(pnlTradeDetails.getQuantity())
                                                                .price(pnlTradeDetails.getPrice())
                                                                .originDateTime(pnlTradeDetails.getOriginDateTime())
                                                                .build())
                                                .collect(toList());

                                        return PnlHistorySnapshot.PnlPortfolioStatementSnapshot.builder()
                                                .portfolioId(pnlPortfolioStatement.getPortfolioId())
                                                .netContributions(pnlPortfolioStatement.getNetContributions())
                                                .currentValue(pnlPortfolioStatement.getCurrentValue())
                                                .totalUnrealisedProfit(pnlPortfolioStatement.getTotalUnrealisedProfit())
                                                .pctUnrealisedProfit(pnlPortfolioStatement.getPctUnrealisedProfit())
                                                .executedTrades(executedTradeSnapshots)
                                                .build();
                                    })
                                    .collect(toList());

                    return new PnlHistorySnapshot.PnlStatementSnapshot(
                            pnlStatement.getNetContributions(),
                            pnlStatement.getCurrentValue(),
                            pnlStatement.getTotalUnrealisedProfit(),
                            pnlStatement.getPctUnrealisedProfit(),
                            portfolioStatementSnapshots,
                            pnlStatement.getDateTime()
                    );
                }).collect(toList());

        return new PnlHistorySnapshot(
                pnlId,
                userId,
                pnlStatementSnapshots
        );
    }

    public static PnlHistory from(PnlHistorySnapshot snapshot) {

        List<PnlStatement> pnlStatements2 = snapshot.getPnlStatements().stream()
                .map(pnlStatementSnapshot -> {
                    List<PnlPortfolioStatement> pnlPortfolioStatements = pnlStatementSnapshot.getPortfolioStatements().stream()
                            .map(pnlPortfolioStatementSnapshot -> {
                                List<PnlTradeDetails> executedTrades = pnlPortfolioStatementSnapshot.getExecutedTrades().stream()
                                        .map(pnlTradeDetailsSnapshot -> PnlTradeDetails.builder()
                                                .originTradeId(pnlTradeDetailsSnapshot.getOriginTradeId())
                                                .tradeId(pnlTradeDetailsSnapshot.getTradeId())
                                                .portfolioId(pnlTradeDetailsSnapshot.getPortfolioId())
                                                .symbol(pnlTradeDetailsSnapshot.getSymbol())
                                                .subName(pnlTradeDetailsSnapshot.getSubName())
                                                .side(pnlTradeDetailsSnapshot.getSide())
                                                .quantity(pnlTradeDetailsSnapshot.getQuantity())
                                                .price(pnlTradeDetailsSnapshot.getPrice())
                                                .originDateTime(pnlTradeDetailsSnapshot.getOriginDateTime())
                                                .build())
                                        .collect(toList());

                                return PnlPortfolioStatement.builder()
                                        .portfolioId(pnlPortfolioStatementSnapshot.getPortfolioId())
                                        .netContributions(pnlPortfolioStatementSnapshot.getNetContributions())
                                        .currentValue(pnlPortfolioStatementSnapshot.getCurrentValue())
                                        .totalUnrealisedProfit(pnlPortfolioStatementSnapshot.getTotalUnrealisedProfit())
                                        .pctUnrealisedProfit(pnlPortfolioStatementSnapshot.getPctUnrealisedProfit())
                                        .executedTrades(executedTrades)
                                        .build();
                            })
                            .collect(toList());
                    return PnlStatement.builder()
                            .netContributions(pnlStatementSnapshot.getNetContributions())
                            .currentValue(pnlStatementSnapshot.getCurrentValue())
                            .totalUnrealisedProfit(pnlStatementSnapshot.getTotalUnrealisedProfit())
                            .pctUnrealisedProfit(pnlStatementSnapshot.getPctUnrealisedProfit())
                            .pnlPortfolioStatements(pnlPortfolioStatements)
                            .dateTime(pnlStatementSnapshot.getDateTime())
                            .build();
                })
                .collect(toList());

        return PnlHistory.builder()
                .pnlId(snapshot.getPnlId())
                .userId(snapshot.getUserId())
                .pnlStatements(pnlStatements2)
                .build();
    }
}
