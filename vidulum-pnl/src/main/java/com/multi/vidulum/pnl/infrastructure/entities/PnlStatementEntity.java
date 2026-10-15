package com.multi.vidulum.pnl.infrastructure.entities;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Value;

import java.util.Date;
import java.util.List;

@Value
@Builder
public class PnlStatementEntity {
    /** Null on documents written before C17 — they cannot say what they were denominated in. */
    ValuationBasisEntity basis;
    Money netContributions;
    Money currentValue;
    Money totalUnrealisedProfit;
    Double pctUnrealisedProfit;
    List<PnlPortfolioStatementEntity> portfolioStatements;
    Date dateTime;
}
