package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;

@Data
@Builder
public class PnlStatement {

    /**
     * What these amounts are expressed in, and what that currency was worth at the time (C17).
     *
     * <p>Absent only on statements written before C17, which cannot say — the reason the field
     * exists. Every figure below is denominated in {@code basis.currency()}, the portfolio
     * statements included, so one statement never mixes currencies.
     */
    private ValuationBasis basis;

    /** Absent when the portfolio summary could not add one up - see {@code ContributionStatus}. */
    private Money netContributions;
    private Money currentValue;
    private Money totalUnrealisedProfit;
    private Double pctUnrealisedProfit;
    private List<PnlPortfolioStatement> pnlPortfolioStatements;
    private ZonedDateTime dateTime;
}
