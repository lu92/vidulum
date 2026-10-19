package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.common.Money;
import jakarta.validation.Valid;
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
public class UpdateRuleRequest {

    @NotBlank(message = "Name is required")
    private String name;

    private String description;

    @NotNull(message = "Base amount is required")
    private Money baseAmount;

    @NotBlank(message = "Category is required")
    private String category;

    /**
     * {@code @Valid} as well as {@code @NotNull}, for the same reason as on
     * {@link CreateRuleRequest#getPattern()}: without it the pattern object is checked for being
     * present and nothing inside it is checked at all. This one was the gap left behind when create
     * was fixed - {@code PatternDto.toPattern()} switches on the type, so a null answered 500.
     */
    @Valid
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
