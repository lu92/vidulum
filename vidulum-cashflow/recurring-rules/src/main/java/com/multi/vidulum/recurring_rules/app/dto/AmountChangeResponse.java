package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.AmountChange;
import com.multi.vidulum.recurring_rules.domain.AmountChangeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmountChangeResponse {

    private String id;
    private Money amount;
    private AmountChangeType type;
    private String reason;

    public static AmountChangeResponse from(AmountChange amountChange) {
        return AmountChangeResponse.builder()
                .id(amountChange.id().id())
                .amount(amountChange.amount())
                .type(amountChange.type())
                .reason(amountChange.reason())
                .build();
    }
}
