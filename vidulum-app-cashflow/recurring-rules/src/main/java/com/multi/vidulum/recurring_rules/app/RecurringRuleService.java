package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.commands.*;
import com.multi.vidulum.recurring_rules.app.queries.*;
import com.multi.vidulum.recurring_rules.app.dto.DashboardResponse;
import com.multi.vidulum.recurring_rules.app.dto.DeleteImpactPreviewResponse;
import com.multi.vidulum.recurring_rules.app.dto.UpcomingTransactionsResponse;
import com.multi.vidulum.recurring_rules.domain.*;
import com.multi.vidulum.recurring_rules.domain.exceptions.CashFlowCommunicationException;
import com.multi.vidulum.recurring_rules.domain.exceptions.CategoryNotFoundException;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDateRangeException;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRuleStateException;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import com.multi.vidulum.recurring_rules.infrastructure.CashFlowHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringRuleService {

    private final DomainRecurringRuleRepository ruleRepository;
    private final CashFlowHttpClient cashFlowHttpClient;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    private static final int FORECAST_MONTHS = 12; // Current + 11 months

    // Command Handlers

    // handle(CreateRuleCommand) and handle(UpdateRuleCommand) left this class for
    // app/commands/create and app/commands/update - a folder per command with its own
    // CommandHandler, the shape cashflow has used from the start. They were the two that asked
    // cashflow's contract, so CashFlowModuleApi left with them; what they shared with the methods
    // below is now RecurringRuleExecutor.

    public void handle(PauseRuleCommand command, String authToken) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (!rule.isActive()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "pause");
        }

        // Clear pending cash changes before pausing - paused rule should not have future forecasts
        executor.clear(rule);

        PauseInfo pauseInfo = command.resumeDate() != null
                ? PauseInfo.untilDate(clock.instant(), command.resumeDate(), command.reason())
                : PauseInfo.indefinite(clock.instant(), command.reason());

        rule.pause(pauseInfo, clock);
        ruleRepository.save(rule);

        log.info("Paused recurring rule {} until {} (cleared pending cash changes)", ruleId.id(),
                command.resumeDate() != null ? command.resumeDate() : "indefinitely");
    }


    public void handle(ResumeRuleCommand command, String authToken) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (!rule.isPaused()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "resume");
        }

        rule.resume(clock);
        ruleRepository.save(rule);

        // Defensive: clear any remaining pending cash changes (edge case - should be empty after proper pause)
        executor.clear(rule);
        // Generate new expected cash changes from today
        executor.generate(rule);

        log.info("Resumed recurring rule {} (regenerated cash changes)", ruleId.id());
    }

    /**
     * Auto-resumes a paused rule. Called by the scheduler when resumeDate is reached.
     * This method does NOT require an auth token as it uses internal/system access.
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

        rule.resume(clock);
        ruleRepository.save(rule);

        // Note: Cash changes are NOT generated here because we don't have an auth token
        // The scheduler will need to handle this separately or use a system token
        log.info("Auto-resumed rule {} as of {} (cash changes need to be regenerated)", ruleId.id(), resumeDate);
    }

    /**
     * Returns all paused rules that should be auto-resumed on or before the given date.
     */
    public List<RecurringRule> findRulesForAutoResume(LocalDate date) {
        return ruleRepository.findPausedRulesWithResumeDateOnOrBefore(date);
    }

    public void handle(DeleteRuleCommand command, String authToken) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (rule.isDeleted()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "delete");
        }

        // Clear generated cash changes
        executor.clear(rule);

        rule.delete(command.reason(), clock);
        ruleRepository.save(rule);

        log.info("Deleted recurring rule {}", ruleId.id());
    }


    public void handle(GenerateExpectedCashChangesCommand command, String authToken) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (!rule.isActive()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "generate expected cash changes");
        }

        // Clear old and generate new
        executor.clear(rule);
        executor.generate(rule);
    }

    public AmountChangeId handle(AddAmountChangeCommand command, String authToken) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (rule.isDeleted()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "add amount change");
        }

        // Generate ID
        long sequence = ruleRepository.generateNextAmountChangeSequence();
        AmountChangeId changeId = AmountChangeId.generate(sequence);

        AmountChange amountChange = new AmountChange(
                changeId,
                command.amount(),
                command.type(),
                command.reason()
        );

        rule.addAmountChange(amountChange, clock);
        ruleRepository.save(rule);

        // Regenerate expected cash changes if rule is active
        if (rule.isActive()) {
            executor.clear(rule);
            executor.generate(rule);
        }

        log.info("Added {} amount change {} to rule {}", command.type(), changeId.id(), ruleId.id());
        return changeId;
    }

    public void handle(RemoveAmountChangeCommand command, String authToken) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (rule.isDeleted()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "remove amount change");
        }

        AmountChangeId changeId = AmountChangeId.of(command.amountChangeId());
        rule.removeAmountChange(changeId, clock);
        ruleRepository.save(rule);

        // Regenerate expected cash changes if rule is active
        if (rule.isActive()) {
            executor.clear(rule);
            executor.generate(rule);
        }

        log.info("Removed amount change {} from rule {}", changeId.id(), ruleId.id());
    }

    // Query Handlers


    public RecurringRuleSnapshot handle(GetRuleQuery query)  {
        RecurringRuleId ruleId = RecurringRuleId.of(query.ruleId());
        return findRuleOrThrow(ruleId).getSnapshot();
    }


    public List<RecurringRuleSnapshot> handle(GetRulesByCashFlowQuery query) {
        CashFlowId cashFlowId = CashFlowId.of(query.cashFlowId());
        return ruleRepository.findByCashFlowId(cashFlowId).stream()
                .map(RecurringRule::getSnapshot)
                .toList();
    }


    public List<RecurringRuleSnapshot> handle(GetRulesByUserQuery query) {
        UserId userId = UserId.of(query.userId());
        return ruleRepository.findByUserId(userId).stream()
                .map(RecurringRule::getSnapshot)
                .toList();
    }

    public DeleteImpactPreviewResponse handle(PreviewDeleteImpactQuery query) {
        RecurringRuleId ruleId = RecurringRuleId.of(query.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        // 1. Calculate future occurrences (what would be removed from forecast)
        LocalDate today = LocalDate.now(clock);
        YearMonth currentMonth = YearMonth.from(today);
        LocalDate forecastEnd = currentMonth.plusMonths(FORECAST_MONTHS - 1).atEndOfMonth();

        List<LocalDate> futureOccurrences = rule.isActive()
                ? rule.generateOccurrences(today, forecastEnd)
                : List.of();

        // Calculate total amount for future occurrences
        BigDecimal totalFutureAmount = BigDecimal.ZERO;
        String currency = rule.getBaseAmount().getCurrency();
        for (LocalDate occurrence : futureOccurrences) {
            Money effectiveAmount = rule.calculateEffectiveAmount(occurrence);
            totalFutureAmount = totalFutureAmount.add(effectiveAmount.getAmount().abs());
        }

        DeleteImpactPreviewResponse.DateRange dateRange = futureOccurrences.isEmpty()
                ? null
                : DeleteImpactPreviewResponse.DateRange.builder()
                        .from(futureOccurrences.get(0))
                        .to(futureOccurrences.get(futureOccurrences.size() - 1))
                        .build();

        DeleteImpactPreviewResponse.FutureOccurrences futureOccurrencesInfo =
                DeleteImpactPreviewResponse.FutureOccurrences.builder()
                        .count(futureOccurrences.size())
                        .totalAmount(Money.of(totalFutureAmount, currency))
                        .dateRange(dateRange)
                        .build();

        // 2. Get generated transaction statuses from CashFlow
        List<CashChangeId> generatedIds = rule.getGeneratedCashChangeIds();
        int pendingCount = 0;
        int confirmedCount = 0;

        if (!generatedIds.isEmpty()) {
            Map<CashChangeId, CashFlowHttpClient.CashChangeStatusInfo> statuses =
                    cashFlowHttpClient.getCashChangeStatuses(rule.getCashFlowId(), ruleId, query.authToken());

            for (CashFlowHttpClient.CashChangeStatusInfo statusInfo : statuses.values()) {
                if (statusInfo.isPending()) {
                    pendingCount++;
                } else if (statusInfo.isConfirmed()) {
                    confirmedCount++;
                }
            }
        }

        DeleteImpactPreviewResponse.GeneratedTransactions generatedTransactions =
                DeleteImpactPreviewResponse.GeneratedTransactions.builder()
                        .total(generatedIds.size())
                        .pending(pendingCount)
                        .confirmed(confirmedCount)
                        .deletable(pendingCount)
                        .build();

        // 3. Calculate forecast impact (affected months)
        Set<String> affectedMonths = futureOccurrences.stream()
                .map(date -> YearMonth.from(date).format(DateTimeFormatter.ofPattern("yyyy-MM")))
                .collect(Collectors.toSet());

        DeleteImpactPreviewResponse.ForecastImpact forecastImpact =
                DeleteImpactPreviewResponse.ForecastImpact.builder()
                        .affectedMonths(affectedMonths.stream().sorted().toList())
                        .balanceReduction(Money.of(totalFutureAmount, currency))
                        .build();

        DeleteImpactPreviewResponse.ImpactDetails impactDetails =
                DeleteImpactPreviewResponse.ImpactDetails.builder()
                        .futureOccurrences(futureOccurrencesInfo)
                        .generatedTransactions(generatedTransactions)
                        .forecastImpact(forecastImpact)
                        .build();

        // 4. Generate warnings
        List<DeleteImpactPreviewResponse.Warning> warnings = new ArrayList<>();

        if (confirmedCount > 0) {
            warnings.add(DeleteImpactPreviewResponse.Warning.builder()
                    .type("CONFIRMED_TRANSACTIONS")
                    .message(String.format("%d potwierdzone transakcje pozostaną bez zmian", confirmedCount))
                    .severity("INFO")
                    .build());
        }

        // Warning for high value rules
        BigDecimal highValueThreshold = new BigDecimal("10000");
        if (totalFutureAmount.compareTo(highValueThreshold) > 0) {
            warnings.add(DeleteImpactPreviewResponse.Warning.builder()
                    .type("HIGH_VALUE")
                    .message(String.format("Ta reguła generuje transakcje o łącznej wartości %.2f %s", totalFutureAmount, currency))
                    .severity("WARNING")
                    .build());
        }

        // 5. Generate recommendations
        List<String> recommendations = new ArrayList<>();

        if (rule.isActive() && futureOccurrences.size() > 3) {
            recommendations.add("Rozważ wstrzymanie reguły zamiast usuwania, jeśli planujesz ją reaktywować w przyszłości");
        }

        if (confirmedCount > 0) {
            recommendations.add("Potwierdzone transakcje pozostaną w CashFlow jako historyczne dane");
        }

        return DeleteImpactPreviewResponse.builder()
                .ruleId(ruleId.id())
                .ruleName(rule.getName())
                .impact(impactDetails)
                .warnings(warnings)
                .recommendations(recommendations)
                .build();
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

    // Private helper methods

    private RecurringRule findRuleOrThrow(RecurringRuleId ruleId)  {
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));
    }
}
