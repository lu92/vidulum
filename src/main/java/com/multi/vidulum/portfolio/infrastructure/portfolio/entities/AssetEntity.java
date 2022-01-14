package com.multi.vidulum.portfolio.infrastructure.portfolio.entities;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AssetEntity {
    String ticker;
    String fullName;
    String segment;
    String subName;
    Money avgPurchasePrice;
    Quantity quantity;
    Quantity locked;
    Quantity free;
    List<String> tags;
}
