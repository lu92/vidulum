package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.common.Money;
import lombok.Builder;

import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for previewing the impact of deleting a recurring rule.
 * Shows what will happen to the forecast and generated transactions.
 */
@Builder
public record DeleteImpactPreviewResponse(
        String ruleId,
        String ruleName,
        ImpactDetails impact,
        List<Warning> warnings,
        List<String> recommendations
) {

    /**
     * Details about the impact of deleting the rule.
     */
    @Builder
    public record ImpactDetails(
            FutureOccurrences futureOccurrences,
            GeneratedTransactions generatedTransactions,
            ForecastImpact forecastImpact
    ) {}

    /**
     * Information about future occurrences that would be removed from forecast.
     */
    @Builder
    public record FutureOccurrences(
            int count,
            Money totalAmount,
            DateRange dateRange
    ) {}

    /**
     * Statistics about already generated transactions.
     */
    @Builder
    public record GeneratedTransactions(
            int total,
            int pending,      // Can be deleted
            int confirmed,    // Protected, cannot be deleted
            int deletable     // Same as pending
    ) {}

    /**
     * Impact on the cash flow forecast.
     */
    @Builder
    public record ForecastImpact(
            List<String> affectedMonths,  // ["2026-03", "2026-04", ...]
            Money balanceReduction
    ) {}

    /**
     * Date range for future occurrences.
     */
    @Builder
    public record DateRange(
            LocalDate from,
            LocalDate to
    ) {}

    /**
     * Warning message with severity level.
     */
    @Builder
    public record Warning(
            String type,      // "CONFIRMED_TRANSACTIONS", "HIGH_VALUE", etc.
            String message,
            String severity   // "INFO", "WARNING", "CRITICAL"
    ) {}
}
