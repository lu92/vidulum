package com.multi.vidulum.risk_management.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Data
@Builder
public class AssetRiskManagementStatement {
    private Ticker ticker;
    private Quantity quantity;
    private List<StopLoss> stopLosses;
    private Money avgPurchasePrice;
    private Money currentPrice;
    private Money riskMoney;
    private RagStatus ragStatus;
    private double pctRiskOfPortfolio;

    public Money calculateRiskMoney() {
        AtomicReference<Quantity> quantityWithoutStopLoss = new AtomicReference<>(quantity.copy());

        Money riskMoney = stopLosses.stream()
                .map(stopLoss -> {
                    Money riskyPrice = avgPurchasePrice.minus(stopLoss.getPrice());
                    Quantity riskyQuantity = quantityWithoutStopLoss.get().minus(stopLoss.getQuantity());
                    quantityWithoutStopLoss.set(riskyQuantity);
                    return riskyPrice.multiply(stopLoss.getQuantity().getQty());
                })
                .reduce(
                        Money.zero("USD"),
                        Money::plus);

        if (!quantityWithoutStopLoss.get().isZero()) {
            Money riskMoneyWithoutStopLoss = avgPurchasePrice.multiply(quantityWithoutStopLoss.get().getQty());
            riskMoney.plus(riskMoneyWithoutStopLoss);
        }

        return riskMoney;
    }
}
