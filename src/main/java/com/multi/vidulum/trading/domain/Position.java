package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.RiskRewardRatio;
import com.multi.vidulum.common.Symbol;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Position {

    Symbol symbol;
    Money targetPrice;
    Money entryPrice;
    Money stopLoss;
    Quantity quantity;

    public static Position combine(Position position1, Position position2) {
        if (!position1.getSymbol().equals(position2.getSymbol())) {
            throw new IllegalArgumentException("Cannot combine positions with different symbol!");
        }

        return Position.builder()
                .symbol(position1.getSymbol())
                .targetPrice(position1.getTargetPrice().plus(position2.getTargetPrice()).divide(2))
                .entryPrice(position1.getEntryPrice().plus(position2.getEntryPrice()).divide(2))
                .stopLoss(position1.getStopLoss().plus(position2.getStopLoss()).divide(2))
                .build();
    }
    public RiskRewardRatio calculateRiskRewardRatio() {
        Money risk = entryPrice.minus(stopLoss);
        Money reward = targetPrice.minus(entryPrice);
        return RiskRewardRatio.of(risk, reward);
    }

    public Money getTargetValue() {
        return quantity.getQty() == 0 ? Money.zero("USD") : targetPrice.multiply(quantity.getQty());
    }

    public Money getEntryValue() {
        return quantity.getQty() == 0 ? Money.zero("USD") : entryPrice.multiply(quantity.getQty());
    }

    public Money getStopLossValue() {
        return quantity.getQty() == 0 ? Money.zero("USD") : stopLoss.multiply(quantity.getQty());
    }

    public Money calculateRisk() {
        return entryPrice.minus(stopLoss).multiply(quantity.getQty());
    }

    public Money calculateReward() {
        return targetPrice.minus(entryPrice).multiply(quantity.getQty());
    }

    // position sizing

    // fix percentage per trade
    // fixed-dollar amount per trade
    // volatility-based position sizing

    // zasady: byc przygotowanym na 10 strat z rzedu -> 10 strat z rzedu pozbawi Ciebie 25% calego kapitalu -> nie wiecej niz 2% per trade



}
