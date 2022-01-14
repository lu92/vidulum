package com.multi.vidulum.portfolio.domain.trades;

import com.multi.vidulum.common.*;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AssetPortion {
    Ticker ticker;
    SubName subName;
    Quantity quantity;
    Price price;

    public Money getValue() {
        return price.multiply(quantity);
    }
}
