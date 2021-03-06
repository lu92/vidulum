package com.multi.vidulum.pnl.infrastructure.entities;

import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.domain.PnlHistorySnapshot;
import com.multi.vidulum.pnl.domain.PnlId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

@Builder
@Getter
@ToString
@Document("pnl-history")
public class PnlHistoryEntity {
    @Id
    private String id;
    private String userId;
    private List<PnlStatementEntity> pnlStatements;


    public static PnlHistoryEntity fromSnapshot(PnlHistorySnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.getPnlId())
                .map(PnlId::getId).orElse(null);

        List<PnlStatementEntity> pnlStatementEntities = snapshot.getPnlStatements().stream()
                .map(pnlStatementSnapshot -> {
                    Date date = pnlStatementSnapshot.getDateTime() != null ?
                            Date.from(pnlStatementSnapshot.getDateTime().toInstant()) :
                            null;

                    List<PnlPortfolioStatementEntity> portfolioStatementEntities = pnlStatementSnapshot.getPortfolioStatements().stream()
                            .map(pnlPortfolioStatementSnapshot -> {
                                List<PnlTradeDetailsEntity> executedTrades = pnlPortfolioStatementSnapshot.getExecutedTrades().stream()
                                        .map(pnlTradeDetailsSnapshot -> {
                                            Date originDateTime = pnlTradeDetailsSnapshot.getOriginDateTime() != null ?
                                                    Date.from(pnlTradeDetailsSnapshot.getOriginDateTime().toInstant()) :
                                                    null;

                                            return new PnlTradeDetailsEntity(
                                                    pnlTradeDetailsSnapshot.getOriginTradeId().getId(),
                                                    pnlTradeDetailsSnapshot.getTradeId().getId(),
                                                    pnlTradeDetailsSnapshot.getPortfolioId().getId(),
                                                    pnlTradeDetailsSnapshot.getSymbol().getId(),
                                                    pnlTradeDetailsSnapshot.getSubName().getName(),
                                                    pnlTradeDetailsSnapshot.getSide(),
                                                    pnlTradeDetailsSnapshot.getQuantity(),
                                                    pnlTradeDetailsSnapshot.getPrice(),
                                                    originDateTime
                                            );
                                        })
                                        .collect(toList());

                                return PnlPortfolioStatementEntity.builder()
                                        .portfolioId(pnlPortfolioStatementSnapshot.getPortfolioId().getId())
                                        .investedBalance(pnlPortfolioStatementSnapshot.getInvestedBalance())
                                        .currentValue(pnlPortfolioStatementSnapshot.getCurrentValue())
                                        .totalProfit(pnlPortfolioStatementSnapshot.getTotalProfit())
                                        .pctProfit(pnlPortfolioStatementSnapshot.getPctProfit())
                                        .executedTrades(executedTrades)
                                        .build();
                            })
                            .collect(toList());

                    return new PnlStatementEntity(
                            pnlStatementSnapshot.getInvestedBalance(),
                            pnlStatementSnapshot.getCurrentValue(),
                            pnlStatementSnapshot.getTotalProfit(),
                            pnlStatementSnapshot.getPctProfit(),
                            portfolioStatementEntities,
                            date
                    );
                })
                .collect(toList());

        return PnlHistoryEntity.builder()
                .id(id)
                .userId(snapshot.getUserId().getId())
                .pnlStatements(pnlStatementEntities)
                .build();
    }

    public PnlHistorySnapshot toSnapshot() {
        List<PnlHistorySnapshot.PnlStatementSnapshot> pnlStatementSnapshots = pnlStatements.stream()
                .map(pnlStatementEntity -> {

                    List<PnlHistorySnapshot.PnlPortfolioStatementSnapshot> portfolioStatementSnapshots = pnlStatementEntity.getPortfolioStatements().stream()
                            .map(pnlPortfolioStatementEntity -> {
                                List<PnlHistorySnapshot.PnlTradeDetailsSnapshot> executedTrades = pnlPortfolioStatementEntity.getExecutedTrades().stream()
                                        .map(pnlTradeDetailsEntity -> {
                                            ZonedDateTime originDateTime = pnlTradeDetailsEntity.getOriginDateTime() != null ?
                                                    ZonedDateTime.ofInstant(pnlTradeDetailsEntity.getOriginDateTime().toInstant(), ZoneOffset.UTC) :
                                                    null;

                                            return PnlHistorySnapshot.PnlTradeDetailsSnapshot.builder()
                                                    .originTradeId(OriginTradeId.of(pnlTradeDetailsEntity.getOriginTradeId()))
                                                    .tradeId(TradeId.of(pnlTradeDetailsEntity.getTradeId()))
                                                    .portfolioId(PortfolioId.of(pnlTradeDetailsEntity.getPortfolioId()))
                                                    .symbol(Symbol.of(pnlTradeDetailsEntity.getSymbol()))
                                                    .subName(SubName.of(pnlTradeDetailsEntity.getSubName()))
                                                    .side(pnlTradeDetailsEntity.getSide())
                                                    .quantity(pnlTradeDetailsEntity.getQuantity())
                                                    .price(pnlTradeDetailsEntity.getPrice())
                                                    .originDateTime(originDateTime)
                                                    .build();
                                        })
                                        .collect(toList());

                                return PnlHistorySnapshot.PnlPortfolioStatementSnapshot.builder()
                                        .portfolioId(PortfolioId.of(pnlPortfolioStatementEntity.getPortfolioId()))
                                        .investedBalance(pnlPortfolioStatementEntity.getInvestedBalance())
                                        .currentValue(pnlPortfolioStatementEntity.getCurrentValue())
                                        .totalProfit(pnlPortfolioStatementEntity.getTotalProfit())
                                        .pctProfit(pnlPortfolioStatementEntity.getPctProfit())
                                        .executedTrades(executedTrades)
                                        .build();
                            })
                            .collect(toList());


                    ZonedDateTime zonedDateTime = pnlStatementEntity.getDateTime() != null ?
                            ZonedDateTime.ofInstant(pnlStatementEntity.getDateTime().toInstant(), ZoneOffset.UTC) :
                            null;

                    return new PnlHistorySnapshot.PnlStatementSnapshot(
                            pnlStatementEntity.getInvestedBalance(),
                            pnlStatementEntity.getCurrentValue(),
                            pnlStatementEntity.getTotalProfit(),
                            pnlStatementEntity.getPctProfit(),
                            portfolioStatementSnapshots,
                            zonedDateTime
                    );
                })
                .collect(toList());

        return new PnlHistorySnapshot(
                PnlId.of(id),
                UserId.of(userId),
                pnlStatementSnapshots
        );
    }
}
