package com.multi.vidulum.portfolio.app.model;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AssetSummary {
    private Ticker ticker;
    private String fullName;
    private Money avgPurchasePrice;
    private double quantity;
    private List<String> tags;

    private double pctProfit;
    private Money profit;
    private Money currentPrice;
    private Money currentValue;
}
