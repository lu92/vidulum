package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;

@Data
@Builder
public class PnlStatement {
    /** Absent when the portfolio summary could not add one up - see {@code ContributionStatus}. */
    private Money netContributions;
    private Money currentValue;
    private Money totalUnrealisedProfit;
    private Double pctUnrealisedProfit;
    private List<PnlPortfolioStatement> pnlPortfolioStatements;
    private ZonedDateTime dateTime;
}
