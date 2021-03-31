package com.multi.vidulum.portfolio.app.model;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PortfolioSummary {
    private PortfolioId portfolioId;
    private UserId userId;
    private String name;
    private List<AssetSummary> assets;
    private Money investedBalance;
    private Money currentValue;
    private double pctProfit;
    private Money profit;
}
