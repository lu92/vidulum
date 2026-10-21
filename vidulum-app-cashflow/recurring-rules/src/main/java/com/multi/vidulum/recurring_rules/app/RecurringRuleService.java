package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.dto.DashboardResponse;
import com.multi.vidulum.recurring_rules.app.dto.UpcomingTransactionsResponse;
import com.multi.vidulum.recurring_rules.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringRuleService {

    private final DomainRecurringRuleRepository ruleRepository;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    // Twelve handle(...) methods left this class: eight to app/commands/{create,update,pause,resume,
    // delete,generate,addamountchange,removeamountchange} and four to app/queries, each with a
    // handler beside its command or query - the shape cashflow has used from the start. They also
    // dropped the authToken parameter and field they never read; what the writes shared is
    // RecurringRuleExecutor.
    //
    // CashFlowModuleApi left with the delete-impact preview, so this class no longer talks to
    // cashflow at all.
    //
    // What is left has no command or query of its own yet: the scheduler's auto-resume, and the
    // dashboard pair, which the controller calls with four loose parameters and which calls each
    // other - so extracting them means deciding where that shared half lives, the way the writes
    // got RecurringRuleExecutor.

    /**
     * Auto-resumes a paused rule. Called by the scheduler when resumeDate is reached.
     *
     * <p>It does what {@code ResumeRuleCommandHandler} does, minus the refusals: a rule that has
     * been deleted or resumed by hand since the scheduler read it is skipped, not an error.
     *
     * <p><b>It now regenerates the predictions, which it used not to.</b> The comment here said
     * "cash changes are NOT generated because we don't have an auth token" - true while the writes
     * went out over HTTP, and left an auto-resumed rule ACTIVE but predicting nothing until someone
     * touched it by hand. The executor takes no token any more, so the reason is gone.
     *
     * @param ruleId the rule ID to auto-resume
     * @param resumeDate the date on which the rule should be resumed (for logging)
     */
    public void handleAutoResume(RecurringRuleId ruleId, LocalDate resumeDate) {
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElse(null);

        if (rule == null) {
            log.warn("Auto-resume: Rule {} not found, skipping", ruleId.id());
            return;
        }

        if (!rule.isPaused()) {
            log.warn("Auto-resume: Rule {} is not paused (status: {}), skipping", ruleId.id(), rule.getStatus());
            return;
        }

        rule.resume(clock.instant());
        ruleRepository.save(rule);

        executor.clear(rule);
        executor.generate(rule);

        log.info("Auto-resumed rule {} as of {} (predictions regenerated)", ruleId.id(), resumeDate);
    }

    /**
     * Returns all paused rules that should be auto-resumed on or before the given date.
     */
    public List<RecurringRule> findRulesForAutoResume(LocalDate date) {
        return ruleRepository.findPausedRulesWithResumeDateOnOrBefore(date);
    }

    // Dashboard and Upcoming Transactions

    public DashboardResponse getDashboard(String userId, String cashFlowId, int upcomingDays, int projectionMonths) {
        UserId userIdObj = UserId.of(userId);
        CashFlowId cashFlowIdObj = CashFlowId.of(cashFlowId);

        // Get rule counts by status (filtered by cashFlowId)
        int activeCount = (int) ruleRepository.countByUserIdAndCashFlowIdAndStatus(userIdObj, cashFlowIdObj, RuleStatus.ACTIVE);
        int pausedCount = (int) ruleRepository.countByUserIdAndCashFlowIdAndStatus(userIdObj, cashFlowIdObj, RuleStatus.PAUSED);
        int completedCount = (int) ruleRepository.countByUserIdAndCashFlowIdAndStatus(userIdObj, cashFlowIdObj, RuleStatus.COMPLETED);

        DashboardResponse.Summary summary = DashboardResponse.Summary.builder()
                .activeRulesCount(activeCount)
                .pausedRulesCount(pausedCount)
                .completedRulesCount(completedCount)
                .build();

        // Calculate projection period based on projectionMonths
        LocalDate today = LocalDate.now(clock);
        YearMonth currentMonth = YearMonth.from(today);
        LocalDate projectionStart = currentMonth.atDay(1);
        LocalDate projectionEnd = currentMonth.plusMonths(projectionMonths - 1).atEndOfMonth();

        DashboardResponse.ProjectionPeriod projectionPeriod = DashboardResponse.ProjectionPeriod.builder()
                .startDate(projectionStart)
                .endDate(projectionEnd)
                .build();

        DashboardResponse.Parameters parameters = DashboardResponse.Parameters.builder()
                .upcomingDays(upcomingDays)
                .projectionMonths(projectionMonths)
                .projectionPeriod(projectionPeriod)
                .build();

        // Calculate monthly projection from active rules (filtered by cashFlowId)
        List<RecurringRule> activeRules = ruleRepository.findByUserIdAndCashFlowIdAndStatus(userIdObj, cashFlowIdObj, RuleStatus.ACTIVE);

        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;
        String currency = "PLN"; // Default currency, will be updated from first rule

        for (RecurringRule rule : activeRules) {
            List<LocalDate> occurrences = rule.generateOccurrences(projectionStart, projectionEnd);
            for (LocalDate occurrence : occurrences) {
                Money effectiveAmount = rule.calculateEffectiveAmount(occurrence);
                currency = effectiveAmount.getCurrency();

                if (effectiveAmount.isPositive()) {
                    totalIncome = totalIncome.add(effectiveAmount.getAmount());
                } else {
                    totalExpenses = totalExpenses.add(effectiveAmount.getAmount().abs());
                }
            }
        }

        DashboardResponse.MonthlyProjection monthlyProjection = DashboardResponse.MonthlyProjection.builder()
                .expenses(Money.of(totalExpenses, currency))
                .income(Money.of(totalIncome, currency))
                .netBalance(Money.of(totalIncome.subtract(totalExpenses), currency))
                .build();

        // Get upcoming transactions (filtered by cashFlowId)
        UpcomingTransactionsResponse upcomingResponse = getUpcomingTransactions(userId, cashFlowId, upcomingDays, 5);

        List<DashboardResponse.UpcomingTransactionDto> upcomingTransactions = upcomingResponse.getTransactions().stream()
                .map(t -> DashboardResponse.UpcomingTransactionDto.builder()
                        .ruleId(t.getRuleId())
                        .ruleName(t.getRuleName())
                        .cashChangeId(t.getCashChangeId())
                        .dueDate(t.getDueDate())
                        .amount(t.getAmount())
                        .type(t.getType())
                        .category(t.getCategory())
                        .daysUntilDue(t.getDaysUntilDue())
                        .build())
                .toList();

        return DashboardResponse.builder()
                .userId(userId)
                .generatedAt(clock.instant())
                .cashFlowId(cashFlowId)
                .parameters(parameters)
                .summary(summary)
                .monthlyProjection(monthlyProjection)
                .upcomingTransactions(upcomingTransactions)
                .build();
    }

    public UpcomingTransactionsResponse getUpcomingTransactions(String userId, String cashFlowId, int days, int limit) {
        UserId userIdObj = UserId.of(userId);
        CashFlowId cashFlowIdObj = CashFlowId.of(cashFlowId);
        LocalDate today = LocalDate.now(clock);
        LocalDate endDate = today.plusDays(days);

        List<RecurringRule> activeRules = ruleRepository.findByUserIdAndCashFlowIdAndStatus(userIdObj, cashFlowIdObj, RuleStatus.ACTIVE);

        List<UpcomingTransactionsResponse.UpcomingTransaction> allTransactions = new ArrayList<>();

        for (RecurringRule rule : activeRules) {
            List<LocalDate> occurrences = rule.generateOccurrences(today, endDate);

            for (LocalDate occurrence : occurrences) {
                Money effectiveAmount = rule.calculateEffectiveAmount(occurrence);
                String type = effectiveAmount.isPositive() ? "INFLOW" : "OUTFLOW";
                int daysUntilDue = (int) java.time.temporal.ChronoUnit.DAYS.between(today, occurrence);

                // Find matching generated cash change ID if exists
                String cashChangeId = findGeneratedCashChangeIdForDate(rule, occurrence);

                UpcomingTransactionsResponse.UpcomingTransaction transaction =
                        UpcomingTransactionsResponse.UpcomingTransaction.builder()
                                .ruleId(rule.getRuleId().id())
                                .ruleName(rule.getName())
                                .cashChangeId(cashChangeId)
                                .dueDate(occurrence)
                                .amount(Money.of(effectiveAmount.getAmount().abs(), effectiveAmount.getCurrency()))
                                .type(type)
                                .category(rule.getCategoryName().name())
                                .daysUntilDue(daysUntilDue)
                                .build();

                allTransactions.add(transaction);
            }
        }

        // Sort by due date and apply limit
        List<UpcomingTransactionsResponse.UpcomingTransaction> sortedTransactions = allTransactions.stream()
                .sorted((a, b) -> a.getDueDate().compareTo(b.getDueDate()))
                .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                .toList();

        // Calculate totals
        BigDecimal totalInflow = BigDecimal.ZERO;
        BigDecimal totalOutflow = BigDecimal.ZERO;
        String currency = "PLN";

        for (UpcomingTransactionsResponse.UpcomingTransaction t : sortedTransactions) {
            currency = t.getAmount().getCurrency();
            if ("INFLOW".equals(t.getType())) {
                totalInflow = totalInflow.add(t.getAmount().getAmount());
            } else {
                totalOutflow = totalOutflow.add(t.getAmount().getAmount());
            }
        }

        return UpcomingTransactionsResponse.builder()
                .transactions(sortedTransactions)
                .totalInflow(Money.of(totalInflow, currency))
                .totalOutflow(Money.of(totalOutflow, currency))
                .netChange(Money.of(totalInflow.subtract(totalOutflow), currency))
                .totalCount(sortedTransactions.size())
                .build();
    }

    /**
     * Finds the generated cash change ID for a specific occurrence date.
     * Returns null if no matching cash change was generated yet.
     */
    private String findGeneratedCashChangeIdForDate(RecurringRule rule, LocalDate occurrenceDate) {
        // This is a simplified implementation - we don't have a direct mapping
        // In a full implementation, we would store the mapping in GenerationInfo
        // For now, return null as the UI can still show upcoming transactions without the ID
        return null;
    }

}
