package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.common.Money;
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
public class DashboardResponse {

    private String userId;
    private Instant generatedAt;
    private Summary summary;
    private MonthlyProjection monthlyProjection;
    private List<UpcomingTransactionDto> upcomingTransactions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private int activeRulesCount;
        private int pausedRulesCount;
        private int completedRulesCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyProjection {
        private Money expenses;
        private Money income;
        private Money netBalance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpcomingTransactionDto {
        private String ruleId;
        private String ruleName;
        private String cashChangeId;
        private LocalDate dueDate;
        private Money amount;
        private String type; // INFLOW or OUTFLOW
        private String category;
        private int daysUntilDue;
    }
}
