package com.multi.vidulum.portfolio.domain.trades;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SellTrade implements Trade, Valuable {
    PortfolioId portfolioId;
    TradeId tradeId;
    Symbol symbol;
    Quantity quantity;
    Money price;

    @Override
    public AssetPortion clarifyPurchasedPortion() {
        return AssetPortion.builder()
                .ticker(symbol.getDestination())
                .quantity(Quantity.of(price.multiply(quantity.getQty()).getAmount().doubleValue()))
                .price(Money.one("USD"))
                .build();
    }

    @Override
    public AssetPortion clarifySoldPortion() {
        return AssetPortion.builder()
                .ticker(symbol.getOrigin())
                .quantity(quantity)
                .price(price)
                .build();
    }

    @Override
    public Money getValue() {
        return price.multiply(quantity.getQty());
    }
}
