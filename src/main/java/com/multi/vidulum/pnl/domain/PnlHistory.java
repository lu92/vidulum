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
                    List<PnlHistorySnapshot.PnlTradeDetailsSnapshot> executedTrades = pnlStatement.getExecutedTrades()
                            .stream()
                            .map(pnlTradeDetails -> new PnlHistorySnapshot.PnlTradeDetailsSnapshot(
                                    pnlTradeDetails.getOriginTradeId(),
                                    pnlTradeDetails.getTradeId(),
                                    pnlTradeDetails.getPortfolioId(),
                                    pnlTradeDetails.getSymbol(),
                                    pnlTradeDetails.getSubName(),
                                    pnlTradeDetails.getSide(),
                                    pnlTradeDetails.getQuantity(),
                                    pnlTradeDetails.getPrice(),
                                    pnlTradeDetails.getOriginDateTime()))
                            .collect(toList());

                    return new PnlHistorySnapshot.PnlStatementSnapshot(
                            pnlStatement.getInvestedBalance(),
                            pnlStatement.getCurrentValue(),
                            pnlStatement.getTotalProfit(),
                            pnlStatement.getPctProfit(),
                            executedTrades,
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
        List<PnlStatement> pnlStatements = snapshot.getPnlStatements().stream()
                .map(pnlStatementSnapshot -> {
                    List<PnlTradeDetails> executedTrades = pnlStatementSnapshot.getExecutedTrades().stream()
                            .map(pnlTradeDetailsSnapshot -> new PnlTradeDetails(
                                    pnlTradeDetailsSnapshot.getOriginTradeId(),
                                    pnlTradeDetailsSnapshot.getTradeId(),
                                    pnlTradeDetailsSnapshot.getPortfolioId(),
                                    pnlTradeDetailsSnapshot.getSymbol(),
                                    pnlTradeDetailsSnapshot.getSubName(),
                                    pnlTradeDetailsSnapshot.getSide(),
                                    pnlTradeDetailsSnapshot.getQuantity(),
                                    pnlTradeDetailsSnapshot.getPrice(),
                                    pnlTradeDetailsSnapshot.getOriginDateTime()
                            ))
                            .collect(toList());
                    return new PnlStatement(
                            pnlStatementSnapshot.getInvestedBalance(),
                            pnlStatementSnapshot.getCurrentValue(),
                            pnlStatementSnapshot.getTotalProfit(),
                            pnlStatementSnapshot.getPctProfit(),
                            executedTrades,
                            pnlStatementSnapshot.getDateTime()
                    );
                })
                .collect(toList());

        return PnlHistory.builder()
                .pnlId(snapshot.getPnlId())
                .userId(snapshot.getUserId())
                .pnlStatements(pnlStatements)
                .build();
    }
}
