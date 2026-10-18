package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.AmountChangeType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddAmountChangeRequest {

    @NotNull(message = "Amount is required")
    private Money amount;

    @NotNull(message = "Type is required (ONE_TIME or PERMANENT)")
    private AmountChangeType type;

    private String reason;
}
