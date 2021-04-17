package com.multi.vidulum.portfolio.domain.trades;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.common.Valuable;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SellTrade implements Valuable {
    PortfolioId portfolioId;
    TradeId tradeId;
    Symbol symbol;
    double quantity;
    Money price;

    @Override
    public Money getValue() {
        return price.multiply(quantity);
    }
}
