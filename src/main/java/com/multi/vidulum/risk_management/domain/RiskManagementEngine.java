package com.multi.vidulum.risk_management.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.TradingRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.user.domain.PortfolioRestClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class RiskManagementEngine {

    private final PortfolioRestClient portfolioRestClient;
    private final TradingRestClient tradingRestClient;
    private final QuoteRestClient quoteRestClient;

    public RiskManagementStatement accept(PortfolioId portfolioId) {
        PortfolioDto.PortfolioSummaryJson portfolio = portfolioRestClient.getPortfolio(portfolioId);
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
                .portfolioId(PortfolioId.of(portfolio.getPortfolioId()))
                .userId(UserId.of(portfolio.getUserId()))
                .name(portfolio.getName())
                .broker(Broker.of(portfolio.getBroker()))
                .assetRiskManagementStatements(assetStatements)
                .investedBalance(portfolio.getInvestedBalance())
                .currentValue(portfolio.getCurrentValue())
                .pctProfit(portfolio.getPctProfit())
                .profit(portfolio.getProfit())
                .safe(totalSafeMoney)
                .risk(totalRiskMoney)
                .riskPct(portfolio.getCurrentValue().diffPct(totalRiskMoney.multiply(-1)))
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

    private List<AssetRiskManagementStatement> buildAssetStatements(PortfolioDto.PortfolioSummaryJson portfolio, List<StopLoss> stopLosses) {
        Broker broker = Broker.of(portfolio.getBroker());
        Map<Ticker, List<StopLoss>> stopLossMap = stopLosses.stream()
                .collect(Collectors.groupingBy(stopLoss -> stopLoss.getSymbol().getOrigin()));

        return portfolio.getAssets().stream()
                .map(asset -> {
                    AtomicReference<Quantity> quantityCoveredWithStopLoss = new AtomicReference<>(Quantity.zero());
                    List<StopLoss> relatedStopLosses = stopLossMap.getOrDefault(Ticker.of(asset.getTicker()), List.of());
                    Money totalAssetRiskMoney = relatedStopLosses.stream()
                            .map(stopLoss -> {
                                Price usdStopLossPriceLevel = fetchUsdPrice(broker, stopLoss);
                                Price riskPriceLevel = asset.getCurrentPrice().minus(usdStopLossPriceLevel);
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

                    Quantity notProtectedQuantity = asset.getQuantity().minus(quantityCoveredWithStopLoss.get());
                    if (notProtectedQuantity.isPositive()) {
                        totalAssetRiskMoney = totalAssetRiskMoney.plus(asset.getCurrentPrice().multiply(notProtectedQuantity));
                    }

                    AssetRiskManagementStatement.AssetRiskManagementStatementBuilder assetRiskManagementStatementBuilder =
                            AssetRiskManagementStatement.builder()
                                    .ticker(Ticker.of(asset.getTicker()))
                                    .quantity(asset.getQuantity())
                                    .stopLosses(relatedStopLosses)
                                    .avgPurchasePrice(asset.getAvgPurchasePrice())
                                    .currentValue(asset.getCurrentValue())
                                    .currentPrice(asset.getCurrentPrice())
                                    .ragStatus(RagStatus.GREEN);

                    if (Ticker.of("USD").equals(Ticker.of(asset.getTicker()))) {
                        assetRiskManagementStatementBuilder
                                .safeMoney(asset.getCurrentValue())
                                .riskMoney(Money.zero("USD"))
                                .pctRiskOfPortfolio(0);

                    } else {
                        assetRiskManagementStatementBuilder
                                .safeMoney(asset.getCurrentValue().minus(totalAssetRiskMoney))
                                .riskMoney(totalAssetRiskMoney)
                                .pctRiskOfPortfolio(portfolio.getCurrentValue().diffPct(totalAssetRiskMoney));
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
            AssetPriceMetadata assetPriceMetadata = quoteRestClient.fetch(broker, usdSymbol);
            return assetPriceMetadata.getCurrentPrice();
        }
    }
}
