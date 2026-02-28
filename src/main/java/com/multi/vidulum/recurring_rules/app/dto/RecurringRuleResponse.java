package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringRuleResponse {

    private String ruleId;
    private String userId;
    private String cashFlowId;
    private String name;
    private String description;
    private Money baseAmount;
    private String category;
    private PatternDto pattern;
    private LocalDate startDate;
    private LocalDate endDate;
    private RuleStatus status;
    private PauseInfoDto pauseInfo;
    private List<String> generatedCashChangeIds;
    private Instant createdAt;
    private Instant lastModifiedAt;

    public static RecurringRuleResponse fromSnapshot(RecurringRuleSnapshot snapshot) {
        return RecurringRuleResponse.builder()
                .ruleId(snapshot.ruleId().id())
                .userId(snapshot.userId().getId())
                .cashFlowId(snapshot.cashFlowId().id())
                .name(snapshot.name())
                .description(snapshot.description())
                .baseAmount(snapshot.baseAmount())
                .category(snapshot.categoryName().name())
                .pattern(PatternDto.fromPattern(snapshot.pattern()))
                .startDate(snapshot.startDate())
                .endDate(snapshot.endDate())
                .status(snapshot.status())
                .pauseInfo(snapshot.pauseInfo() != null ? PauseInfoDto.from(snapshot.pauseInfo()) : null)
                .generatedCashChangeIds(snapshot.generatedCashChangeIds().stream()
                        .map(CashChangeId::id)
                        .toList())
                .createdAt(snapshot.createdAt())
                .lastModifiedAt(snapshot.lastModifiedAt())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PauseInfoDto {
        private Instant pausedAt;
        private LocalDate resumeDate;
        private String reason;

        public static PauseInfoDto from(PauseInfo pauseInfo) {
            return PauseInfoDto.builder()
                    .pausedAt(pauseInfo.pausedAt())
                    .resumeDate(pauseInfo.resumeDate())
                    .reason(pauseInfo.reason())
                    .build();
        }
    }
}
