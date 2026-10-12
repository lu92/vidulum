package com.multi.vidulum.risk_management.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.api.PortfolioModuleApi;
import com.multi.vidulum.portfolio.api.view.PortfolioView;
import com.multi.vidulum.quotation.api.QuotationApi;
import com.multi.vidulum.portfolio.domain.TradingRestClient;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.trading.app.TradingDto;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class RiskManagementEngine {

    private final PortfolioModuleApi portfolioApi;
    private final TradingRestClient tradingRestClient;
    private final QuotationApi quotationApi;

    public RiskManagementStatement accept(PortfolioId portfolioId) {
        PortfolioView portfolio = portfolioApi.get(portfolioId);
        List<TradingDto.OrderSummaryJson> openedOrders = tradingRestClient.getOpenedOrders(portfolioId);
        List<StopLoss> stopLosses = fetchStopLosses(openedOrders);
        List<AssetRiskManagementStatement> assetStatements = buildAssetStatements(portfolio, stopLosses);
        Money totalRiskMoney = assetStatements.stream()
                .map(AssetRiskManagementStatement::getRiskMoney)
                .reduce(Money.zero("USD"), Money::plus);

        Money totalSafeMoney = assetStatements.stream()
                .map(AssetRiskManagementStatement::getSafeMoney)
                .reduce(Money.zero("USD"), Money::plus);


        return RiskManagementStatement.builder()
                .portfolioId(portfolio.portfolioId())
                .userId(portfolio.owner())
                .name(portfolio.name())
                .broker(portfolio.broker())
                .assetRiskManagementStatements(assetStatements)
                .netContributions(portfolio.netContributions())
                .currentValue(portfolio.currentValue())
                .pctUnrealisedProfit(portfolio.pctUnrealisedProfit())
                .unrealisedProfit(portfolio.unrealisedProfit())
                .safe(totalSafeMoney)
                .risk(totalRiskMoney)
                .riskPct(portfolio.currentValue().diffPct(totalRiskMoney.multiply(-1)))
                .build();
    }

    private List<StopLoss> fetchStopLosses(List<TradingDto.OrderSummaryJson> openedOrders) {
        return openedOrders.stream()
                .filter(orderSummaryJson -> Side.SELL.equals(orderSummaryJson.getSide()))
                .map(orderSummaryJson ->
                        StopLoss.builder()
                                .symbol(Symbol.of(orderSummaryJson.getSymbol()))
                                .originOrderId(OrderId.of(orderSummaryJson.getOriginOrderId()))
                                .quantity(orderSummaryJson.getQuantity())
                                .price(orderSummaryJson.getLimitPrice())
                                .dateTime(orderSummaryJson.getOriginDateTime())
                                .build())
                .collect(Collectors.toList());
    }

    private List<AssetRiskManagementStatement> buildAssetStatements(PortfolioView portfolio, List<StopLoss> stopLosses) {
        Broker broker = portfolio.broker();
        Map<Ticker, List<StopLoss>> stopLossMap = stopLosses.stream()
                .collect(Collectors.groupingBy(stopLoss -> stopLoss.getSymbol().getOrigin()));

        return portfolio.positions().stream()
                .map(asset -> {
                    AtomicReference<Quantity> quantityCoveredWithStopLoss = new AtomicReference<>(Quantity.zero());
                    List<StopLoss> relatedStopLosses = stopLossMap.getOrDefault(asset.ticker(), List.of());
                    Money totalAssetRiskMoney = relatedStopLosses.stream()
                            .map(stopLoss -> {
                                Price usdStopLossPriceLevel = fetchUsdPrice(broker, stopLoss);
                                Price riskPriceLevel = asset.currentPrice().minus(usdStopLossPriceLevel);
                                stopLoss.setApplicable(riskPriceLevel.isPositive());
                                if (riskPriceLevel.isPositive()) {
                                    Quantity protectedQuantity = quantityCoveredWithStopLoss.get().plus(stopLoss.getQuantity());
                                    quantityCoveredWithStopLoss.set(protectedQuantity);
                                    return riskPriceLevel.multiply(stopLoss.getQuantity());
                                } else {
                                    return Money.zero("USD");
                                }
                            })
                            .reduce(Money.zero("USD"), Money::plus);

                    Quantity notProtectedQuantity = asset.quantity().minus(quantityCoveredWithStopLoss.get());
                    if (notProtectedQuantity.isPositive()) {
                        totalAssetRiskMoney = totalAssetRiskMoney.plus(asset.currentPrice().multiply(notProtectedQuantity));
                    }

                    AssetRiskManagementStatement.AssetRiskManagementStatementBuilder assetRiskManagementStatementBuilder =
                            AssetRiskManagementStatement.builder()
                                    .ticker(asset.ticker())
                                    .quantity(asset.quantity())
                                    .stopLosses(relatedStopLosses)
                                    .avgPurchasePrice(asset.avgPurchasePrice())
                                    .currentValue(asset.currentValue())
                                    .currentPrice(asset.currentPrice())
                                    .ragStatus(RagStatus.GREEN);

                    if (Ticker.of("USD").equals(asset.ticker())) {
                        assetRiskManagementStatementBuilder
                                .safeMoney(asset.currentValue())
                                .riskMoney(Money.zero("USD"))
                                .pctRiskOfPortfolio(0);

                    } else {
                        assetRiskManagementStatementBuilder
                                .safeMoney(asset.currentValue().minus(totalAssetRiskMoney))
                                .riskMoney(totalAssetRiskMoney)
                                .pctRiskOfPortfolio(portfolio.currentValue().diffPct(totalAssetRiskMoney));
                    }
                    return assetRiskManagementStatementBuilder.build();
                })
                .collect(Collectors.toList());
    }

    private Price fetchUsdPrice(Broker broker, StopLoss stopLoss) {
        boolean isUsdDenominated = stopLoss.getSymbol().getDestination().equals(Ticker.of("USD"));
        if (isUsdDenominated) {
            return stopLoss.getPrice();
        } else {
            Symbol usdSymbol = Symbol.of(stopLoss.getSymbol().getOrigin(), Ticker.of("USD"));
            AssetPriceMetadata assetPriceMetadata = quotationApi.fetch(broker, usdSymbol);
            return assetPriceMetadata.getCurrentPrice();
        }
    }
}
