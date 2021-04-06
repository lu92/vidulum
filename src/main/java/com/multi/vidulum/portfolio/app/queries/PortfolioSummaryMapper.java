package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class PortfolioSummaryMapper {

    private final QuoteRestClient quoteRestClient;

    public PortfolioDto.PortfolioSummaryJson map(Portfolio portfolio) {
        List<PortfolioDto.AssetSummaryJson> assets = portfolio.getAssets()
                .stream()
                .map(this::map)
                .collect(Collectors.toList());


        Money currentValue = assets.stream().reduce(
                Money.zero("USD"),
                (accumulatedValue, assetSummary) -> accumulatedValue.plus(assetSummary.getCurrentValue()),
                Money::plus);

        Money profit = currentValue.minus(portfolio.getInvestedBalance());


        double pctProfit = 0;
        if (!Money.zero("USD").equals(portfolio.getInvestedBalance())) {
            pctProfit = profit.diffPct(portfolio.getInvestedBalance());
        }

        return PortfolioDto.PortfolioSummaryJson.builder()
                .portfolioId(portfolio.getPortfolioId().getId())
                .userId(portfolio.getUserId().getId())
                .name(portfolio.getName())
                .assets(assets)
                .investedBalance(portfolio.getInvestedBalance())
                .currentValue(currentValue)
                .profit(profit)
                .pctProfit(pctProfit)
                .build();
    }

    private PortfolioDto.AssetSummaryJson map(Asset asset) {
        AssetPriceMetadata assetPriceMetadata = quoteRestClient.fetch(asset.getTicker());

        Money oldValue = asset.getAvgPurchasePrice().multiply(asset.getQuantity());
        Money currentValue = assetPriceMetadata.getCurrentPrice().multiply(asset.getQuantity());
        Money profit = currentValue.minus(oldValue);
        double pctProfit = currentValue.diffPct(oldValue);

        return PortfolioDto.AssetSummaryJson.builder()
                .ticker(asset.getTicker().getId())
                .fullName(asset.getFullName())
                .avgPurchasePrice(asset.getAvgPurchasePrice())
                .quantity(asset.getQuantity())
                .tags(asset.getTags())
                .pctProfit(pctProfit)
                .profit(profit)
                .currentPrice(assetPriceMetadata.getCurrentPrice())
                .currentValue(currentValue)
                .build();
    }
}
