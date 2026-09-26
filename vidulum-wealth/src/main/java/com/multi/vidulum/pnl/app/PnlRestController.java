package com.multi.vidulum.pnl.app;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.PortfolioAccess;
import com.multi.vidulum.pnl.app.commands.MakePnlSnapshotCommand;
import com.multi.vidulum.pnl.app.queries.GetPnlHistoryQuery;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.multi.vidulum.pnl.app.queries.GetWealthChangeQuery;
import com.multi.vidulum.pnl.domain.WealthChangeOverWindow;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.ZonedDateTime;
import java.util.List;

import static java.util.stream.Collectors.toList;

@RestController
@AllArgsConstructor
public class PnlRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final PortfolioAccess access;

    /** The caller's own history. The user id used to be a path variable anyone could set. */
    @GetMapping("/pnl")
    public PnlDto.PnlHistoryJson getPnlHistory() {
        GetPnlHistoryQuery query = GetPnlHistoryQuery.builder()
                .userId(access.currentUser())
                .build();

        PnlHistory pnlHistory = queryGateway.send(query);

        return toJson(pnlHistory);
    }

    /**
     * How much the owner's wealth changed since a given moment (task C14).
     *
     * <p>{@code GET /pnl/wealth-change?portfolioId=…&since=2026-09-01T00:00:00Z}. The answer always
     * carries a status: a window starting before the first recorded valuation has no reference
     * point, and saying so beats computing one from today's prices.
     */
    @GetMapping("/pnl/wealth-change")
    public PnlDto.WealthChangeJson getWealthChange(
            @RequestParam String portfolioId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime since) {

        WealthChangeOverWindow change = queryGateway.send(new GetWealthChangeQuery(
                access.currentUser(), access.requireOwned(portfolioId), since));

        return new PnlDto.WealthChangeJson(
                change.since(), change.measuredFrom(), change.change(), change.pct(),
                change.status().name());
    }

    @PostMapping("/pnl")
    public void makePnlSnapshot(@RequestBody PnlDto.MakePnlSnapshotJson request) {
        MakePnlSnapshotCommand command = MakePnlSnapshotCommand.builder()
                .userId(access.currentUser())
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
                                        .netContributions(pnlPortfolioStatement.getNetContributions())
                                        .currentValue(pnlPortfolioStatement.getCurrentValue())
                                        .totalUnrealisedProfit(pnlPortfolioStatement.getTotalUnrealisedProfit())
                                        .pctUnrealisedProfit(pnlPortfolioStatement.getPctUnrealisedProfit())
                                        .executedTrades(executedTrades)
                                        .build();
                            })
                            .collect(toList());

                    return PnlDto.PnlStatementJson.builder()
                            .netContributions(pnlStatement.getNetContributions())
                            .currentValue(pnlStatement.getCurrentValue())
                            .totalUnrealisedProfit(pnlStatement.getTotalUnrealisedProfit())
                            .pctUnrealisedProfit(pnlStatement.getPctUnrealisedProfit())
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
