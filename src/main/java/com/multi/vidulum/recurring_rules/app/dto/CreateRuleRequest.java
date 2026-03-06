package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRuleRequest {

    @NotBlank(message = "User ID is required")
    private String userId;

    @NotBlank(message = "CashFlow ID is required")
    private String cashFlowId;

    @NotBlank(message = "Name is required")
    private String name;

    private String description;

    @NotNull(message = "Base amount is required")
    private Money baseAmount;

    @NotBlank(message = "Category is required")
    private String category;

    @NotNull(message = "Pattern is required")
    private PatternDto pattern;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    private LocalDate endDate;

    /**
     * Maximum number of occurrences before auto-completing the rule.
     * If null, the rule has no occurrence limit.
     */
    @Positive(message = "Max occurrences must be positive")
    private Integer maxOccurrences;

    /**
     * List of months when the rule is active (seasonal rules).
     * If empty or null, the rule is active in all months.
     */
    private List<Month> activeMonths;

    /**
     * List of specific dates to exclude from generation.
     * Useful for holidays or special exceptions.
     */
    private List<LocalDate> excludedDates;
}
