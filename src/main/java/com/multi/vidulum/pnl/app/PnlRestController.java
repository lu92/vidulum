package com.multi.vidulum.pnl.app;

import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.pnl.app.commands.MakePnlSnapshotCommand;
import com.multi.vidulum.pnl.app.queries.GetPnlHistoryQuery;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static java.util.stream.Collectors.toList;

@RestController
@AllArgsConstructor
public class PnlRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    @GetMapping("/pnl/userId={userId}")
    public PnlDto.PnlHistoryJson getPnlHistory(@PathVariable("userId") String userId) {
        GetPnlHistoryQuery query = GetPnlHistoryQuery.builder()
                .userId(UserId.of(userId))
                .build();

        PnlHistory pnlHistory = queryGateway.send(query);

        return toJson(pnlHistory);
    }

    @PostMapping("/pnl")
    public void makePnlSnapshot(@RequestBody PnlDto.MakePnlSnapshotJson request) {
        MakePnlSnapshotCommand command = MakePnlSnapshotCommand.builder()
                .userId(UserId.of(request.getUserId()))
                .dateTimeRange(Range.of(request.getFrom(), request.getTo()))
                .build();

        commandGateway.send(command);
    }

    private PnlDto.PnlHistoryJson toJson(PnlHistory pnlHistory) {
        List<PnlDto.PnlStatementJson> pnlStatements = pnlHistory.getPnlStatements().stream()
                .map(pnlStatement -> {
                    List<PnlDto.PnlPortfolioStatementJson> portfolioStatements = pnlStatement.getPnlPortfolioStatements().stream()
                            .map(pnlPortfolioStatement -> {
                                List<PnlDto.PnlTradeDetailsJson> executedTrades = pnlPortfolioStatement.getExecutedTrades().stream()
                                        .map(pnlTradeDetails -> PnlDto.PnlTradeDetailsJson.builder()
                                                .tradeId(pnlTradeDetails.getTradeId().getId())
                                                .originTradeId(pnlTradeDetails.getOriginTradeId().getId())
                                                .portfolioId(pnlTradeDetails.getPortfolioId().getId())
                                                .symbol(pnlTradeDetails.getSymbol().getId())
                                                .subName(pnlTradeDetails.getSubName().getName())
                                                .side(pnlTradeDetails.getSide())
                                                .quantity(pnlTradeDetails.getQuantity())
                                                .price(pnlTradeDetails.getPrice())
                                                .originDateTime(pnlTradeDetails.getOriginDateTime())
                                                .build())
                                        .collect(toList());

                                return PnlDto.PnlPortfolioStatementJson.builder()
                                        .portfolioId(pnlPortfolioStatement.getPortfolioId().getId())
                                        .investedBalance(pnlPortfolioStatement.getInvestedBalance())
                                        .currentValue(pnlPortfolioStatement.getCurrentValue())
                                        .totalProfit(pnlPortfolioStatement.getTotalProfit())
                                        .pctProfit(pnlPortfolioStatement.getPctProfit())
                                        .executedTrades(executedTrades)
                                        .build();
                            })
                            .collect(toList());

                    return PnlDto.PnlStatementJson.builder()
                            .investedBalance(pnlStatement.getInvestedBalance())
                            .currentValue(pnlStatement.getCurrentValue())
                            .totalProfit(pnlStatement.getTotalProfit())
                            .pctProfit(pnlStatement.getPctProfit())
                            .portfolioStatements(portfolioStatements)
                            .dateTime(pnlStatement.getDateTime())
                            .build();
                })
                .collect(toList());

        return PnlDto.PnlHistoryJson.builder()
                .pnlId(pnlHistory.getPnlId().getId())
                .userId(pnlHistory.getUserId().getId())
                .pnlStatements(pnlStatements)
                .build();
    }
}
