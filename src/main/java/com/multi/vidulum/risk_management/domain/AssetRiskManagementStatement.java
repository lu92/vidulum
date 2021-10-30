package com.multi.vidulum.risk_management.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AssetRiskManagementStatement {
    private Ticker ticker;
    private Quantity quantity;
    private List<StopLoss> stopLosses;
    private Money avgPurchasePrice;
    private Money currentValue;
    private Money currentPrice;
    private Money safeMoney;
    private Money riskMoney;
    private RagStatus ragStatus;
    private double pctRiskOfPortfolio;
}
