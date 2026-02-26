package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

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

    @NotNull(message = "Pattern is required")
    private PatternDto pattern;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    private LocalDate endDate;
}
