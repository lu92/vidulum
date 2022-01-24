package com.multi.vidulum.portfolio.infrastructure.portfolio.entities;

import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AssetEntity {
    String ticker;
    String subName;
    Price avgPurchasePrice;
    Quantity quantity;
    Quantity locked;
    Quantity free;
}
