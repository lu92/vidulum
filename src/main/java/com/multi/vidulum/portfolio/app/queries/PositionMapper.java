package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.trading.domain.OpenedPositions;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.stream.Collectors.toList;

@Component
@AllArgsConstructor
public class PositionMapper {

    private final QuoteRestClient quoteRestClient;

    public PortfolioDto.OpenedPositionsJson map(OpenedPositions openedPositions) {
        List<PortfolioDto.PositionSummaryJson> portfolioSummaries = openedPositions
                .getPositions()
                .stream()
                .map(position -> {
                    AssetPriceMetadata assetPriceMetadata = quoteRestClient.fetch(openedPositions.getBroker(), position.getSymbol());
                    Money value = assetPriceMetadata.getCurrentPrice().multiply(position.getQuantity());
                    assetPriceMetadata.getCurrentPrice().minus(position.getEntryPrice());
                    double pctProfit = assetPriceMetadata.getCurrentPrice().diffPct(position.getEntryPrice());
                    return PortfolioDto.PositionSummaryJson.builder()
                            .symbol(position.getSymbol().getId())
                            .targetPrice(position.getTargetPrice())
                            .entryPrice(position.getEntryPrice())
                            .stopLoss(position.getStopLoss())
                            .quantity(position.getQuantity())
                            .risk(position.calculateRisk())
                            .reward(position.calculateReward())
                            .riskRewardRatio(position.calculateRiskRewardRatio())
                            .value(value)
                            .pctProfit(pctProfit)
                            .build();
                })
                .collect(toList());

        return PortfolioDto.OpenedPositionsJson.builder()
                .portfolioId(openedPositions.getPortfolioId().getId())
                .positions(portfolioSummaries)
                .build();
    }

}
