package com.multi.vidulum.portfolio.infrastructure.portfolio.entities;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AssetEntity {
    String ticker;
    String fullName;
    Money avgPurchasePrice;
    double quantity;
    List<String> tags;
}
