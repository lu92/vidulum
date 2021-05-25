package com.multi.vidulum.portfolio.domain.trades;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AssetPortion {
    Ticker ticker;
    String subName;
    Quantity quantity;
    Money price;

    public Money getValue() {
        return price.multiply(quantity.getQty());
    }
}
