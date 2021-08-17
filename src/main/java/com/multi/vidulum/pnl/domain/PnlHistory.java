package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.Builder;
import lombok.Data;

import java.util.List;

import static java.util.stream.Collectors.toList;

@Data
@Builder
public class PnlHistory implements Aggregate<PnlId, PnlHistorySnapshot> {
    PnlId pnlId;
    UserId userId;
    List<PnlStatement> pnlStatements;

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
                                                .investedBalance(pnlPortfolioStatement.getInvestedBalance())
                                                .currentValue(pnlPortfolioStatement.getCurrentValue())
                                                .totalProfit(pnlPortfolioStatement.getTotalProfit())
                                                .pctProfit(pnlPortfolioStatement.getPctProfit())
                                                .executedTrades(executedTradeSnapshots)
                                                .build();
                                    })
                                    .collect(toList());

                    return new PnlHistorySnapshot.PnlStatementSnapshot(
                            pnlStatement.getInvestedBalance(),
                            pnlStatement.getCurrentValue(),
                            pnlStatement.getTotalProfit(),
                            pnlStatement.getPctProfit(),
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
                                        .investedBalance(pnlPortfolioStatementSnapshot.getInvestedBalance())
                                        .currentValue(pnlPortfolioStatementSnapshot.getCurrentValue())
                                        .totalProfit(pnlPortfolioStatementSnapshot.getTotalProfit())
                                        .pctProfit(pnlPortfolioStatementSnapshot.getPctProfit())
                                        .executedTrades(executedTrades)
                                        .build();
                            })
                            .collect(toList());
                    return PnlStatement.builder()
                            .investedBalance(pnlStatementSnapshot.getInvestedBalance())
                            .currentValue(pnlStatementSnapshot.getCurrentValue())
                            .totalProfit(pnlStatementSnapshot.getTotalProfit())
                            .pctProfit(pnlStatementSnapshot.getPctProfit())
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
