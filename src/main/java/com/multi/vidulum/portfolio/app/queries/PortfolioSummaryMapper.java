package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.portfolio.app.model.AssetSummary;
import com.multi.vidulum.portfolio.app.model.PortfolioSummary;
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

    public PortfolioSummary mapAsset(Portfolio portfolio) {
        List<AssetSummary> assets = portfolio.getAssets()
                .stream()
                .map(this::mapAsset)
                .collect(Collectors.toList());


        Money currentValue = assets.stream().reduce(
                Money.zero("USD"),
                (accumulatedValue, assetSummary) -> accumulatedValue.plus(assetSummary.getCurrentValue()),
                Money::plus);

        Money profit = currentValue.minus(portfolio.getInvestedBalance());

        double pctProfit = profit.diffPct(portfolio.getInvestedBalance());

        return PortfolioSummary.builder()
                .portfolioId(portfolio.getPortfolioId())
                .userId(portfolio.getUserId())
                .name(portfolio.getName())
                .assets(assets)
                .investedBalance(portfolio.getInvestedBalance())
                .currentValue(currentValue)
                .profit(profit)
                .pctProfit(pctProfit)
                .build();
    }

    private AssetSummary mapAsset(Asset asset) {
        AssetPriceMetadata assetPriceMetadata = quoteRestClient.fetch(asset.getTicker());

        Money oldValue = asset.getAvgPurchasePrice().multiply(asset.getQuantity());
        Money currentValue = assetPriceMetadata.getCurrentPrice().multiply(asset.getQuantity());
        Money profit = currentValue.minus(oldValue);
        double pctProfit = currentValue.diffPct(oldValue);

        return AssetSummary.builder()
                .ticker(asset.getTicker())
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
