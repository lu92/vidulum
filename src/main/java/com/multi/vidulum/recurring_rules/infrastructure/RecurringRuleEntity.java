package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.domain.*;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Builder
@Getter
@ToString
@Document("recurring-rules")
public class RecurringRuleEntity {

    @Id
    private String ruleId;

    @Indexed
    private String userId;

    @Indexed
    private String cashFlowId;

    private String name;
    private String description;
    private Money baseAmount;
    private String categoryName;
    private PatternEmbedded pattern;
    private LocalDate startDate;
    private LocalDate endDate;
    private RuleStatus status;
    private PauseInfoEmbedded pauseInfo;
    private List<AmountChangeEmbedded> amountChanges;
    private List<RuleExecutionEmbedded> executions;
    private List<String> generatedCashChangeIds;
    private Date createdAt;
    private Date lastModifiedAt;

    public static RecurringRuleEntity fromSnapshot(RecurringRuleSnapshot snapshot) {
        return RecurringRuleEntity.builder()
                .ruleId(snapshot.ruleId().id())
                .userId(snapshot.userId().getId())
                .cashFlowId(snapshot.cashFlowId().id())
                .name(snapshot.name())
                .description(snapshot.description())
                .baseAmount(snapshot.baseAmount())
                .categoryName(snapshot.categoryName().name())
                .pattern(PatternEmbedded.from(snapshot.pattern()))
                .startDate(snapshot.startDate())
                .endDate(snapshot.endDate())
                .status(snapshot.status())
                .pauseInfo(snapshot.pauseInfo() != null ? PauseInfoEmbedded.from(snapshot.pauseInfo()) : null)
                .amountChanges(snapshot.amountChanges().stream()
                        .map(AmountChangeEmbedded::from)
                        .collect(Collectors.toList()))
                .executions(snapshot.executions().stream()
                        .map(RuleExecutionEmbedded::from)
                        .collect(Collectors.toList()))
                .generatedCashChangeIds(snapshot.generatedCashChangeIds().stream()
                        .map(CashChangeId::id)
                        .collect(Collectors.toList()))
                .createdAt(Date.from(snapshot.createdAt()))
                .lastModifiedAt(Date.from(snapshot.lastModifiedAt()))
                .build();
    }

    public RecurringRuleSnapshot toSnapshot() {
        return new RecurringRuleSnapshot(
                RecurringRuleId.of(ruleId),
                UserId.of(userId),
                CashFlowId.of(cashFlowId),
                name,
                description,
                baseAmount,
                new CategoryName(categoryName),
                pattern.toPattern(),
                startDate,
                endDate,
                status,
                pauseInfo != null ? pauseInfo.toPauseInfo() : null,
                amountChanges.stream().map(AmountChangeEmbedded::toAmountChange).collect(Collectors.toList()),
                executions.stream().map(RuleExecutionEmbedded::toRuleExecution).collect(Collectors.toList()),
                generatedCashChangeIds.stream().map(CashChangeId::new).collect(Collectors.toList()),
                createdAt.toInstant(),
                lastModifiedAt.toInstant()
        );
    }

    // Embedded documents
    @Builder
    @Getter
    public static class PatternEmbedded {
        private RecurrenceType type;
        // Daily
        private Integer intervalDays;
        // Weekly
        private DayOfWeek dayOfWeek;
        private Integer intervalWeeks;
        // Monthly
        private Integer dayOfMonth;
        private Integer intervalMonths;
        private Boolean adjustForMonthEnd;
        // Yearly
        private Integer month;
        private Integer yearlyDayOfMonth;

        public static PatternEmbedded from(RecurrencePattern pattern) {
            PatternEmbedded.PatternEmbeddedBuilder builder = PatternEmbedded.builder()
                    .type(pattern.type());

            return switch (pattern) {
                case DailyPattern daily -> builder.intervalDays(daily.intervalDays()).build();
                case WeeklyPattern weekly -> builder
                        .dayOfWeek(weekly.dayOfWeek())
                        .intervalWeeks(weekly.intervalWeeks())
                        .build();
                case MonthlyPattern monthly -> builder
                        .dayOfMonth(monthly.dayOfMonth())
                        .intervalMonths(monthly.intervalMonths())
                        .adjustForMonthEnd(monthly.adjustForMonthEnd())
                        .build();
                case YearlyPattern yearly -> builder
                        .month(yearly.month())
                        .yearlyDayOfMonth(yearly.dayOfMonth())
                        .build();
            };
        }

        public RecurrencePattern toPattern() {
            return switch (type) {
                case DAILY -> new DailyPattern(intervalDays);
                case WEEKLY -> new WeeklyPattern(dayOfWeek, intervalWeeks);
                case MONTHLY -> new MonthlyPattern(dayOfMonth, intervalMonths, adjustForMonthEnd != null && adjustForMonthEnd);
                case YEARLY -> new YearlyPattern(month, yearlyDayOfMonth);
            };
        }
    }

    @Builder
    @Getter
    public static class PauseInfoEmbedded {
        private Date pausedAt;
        private LocalDate resumeDate;
        private String reason;

        public static PauseInfoEmbedded from(PauseInfo pauseInfo) {
            return PauseInfoEmbedded.builder()
                    .pausedAt(Date.from(pauseInfo.pausedAt()))
                    .resumeDate(pauseInfo.resumeDate())
                    .reason(pauseInfo.reason())
                    .build();
        }

        public PauseInfo toPauseInfo() {
            if (resumeDate != null) {
                return PauseInfo.untilDate(pausedAt.toInstant(), resumeDate, reason);
            }
            return PauseInfo.indefinite(pausedAt.toInstant(), reason);
        }
    }

    @Builder
    @Getter
    public static class AmountChangeEmbedded {
        private String id;
        private Money amount;
        private AmountChangeType type;
        private String reason;

        public static AmountChangeEmbedded from(AmountChange amountChange) {
            return AmountChangeEmbedded.builder()
                    .id(amountChange.id().id())
                    .amount(amountChange.amount())
                    .type(amountChange.type())
                    .reason(amountChange.reason())
                    .build();
        }

        public AmountChange toAmountChange() {
            return new AmountChange(AmountChangeId.of(id), amount, type, reason);
        }
    }

    @Builder
    @Getter
    public static class RuleExecutionEmbedded {
        private LocalDate executionDate;
        private Date executedAt;
        private ExecutionStatus status;
        private String generatedCashChangeId;
        private Money executedAmount;
        private String failureReason;

        public static RuleExecutionEmbedded from(RuleExecution execution) {
            return RuleExecutionEmbedded.builder()
                    .executionDate(execution.executionDate())
                    .executedAt(Date.from(execution.executedAt()))
                    .status(execution.status())
                    .generatedCashChangeId(execution.generatedCashChangeId() != null ?
                            execution.generatedCashChangeId().id() : null)
                    .executedAmount(execution.executedAmount())
                    .failureReason(execution.failureReason())
                    .build();
        }

        public RuleExecution toRuleExecution() {
            return new RuleExecution(
                    executionDate,
                    executedAt.toInstant(),
                    status,
                    generatedCashChangeId != null ? new CashChangeId(generatedCashChangeId) : null,
                    executedAmount,
                    failureReason
            );
        }
    }
}
