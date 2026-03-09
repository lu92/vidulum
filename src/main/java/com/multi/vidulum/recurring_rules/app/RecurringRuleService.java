package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.commands.*;
import com.multi.vidulum.recurring_rules.app.queries.*;
import com.multi.vidulum.recurring_rules.app.dto.DeleteImpactPreviewResponse;
import com.multi.vidulum.recurring_rules.domain.*;
import com.multi.vidulum.recurring_rules.domain.exceptions.*;
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
    private final Clock clock;

    private static final int FORECAST_MONTHS = 12; // Current + 11 months

    // Command Handlers

    public RecurringRuleId handle(CreateRuleCommand command, String authToken) throws RecurringRuleException {
        CashFlowId cashFlowId = CashFlowId.of(command.cashFlowId());

        // Validate CashFlow exists
        CashFlowHttpClient.CashFlowInfo cashFlowInfo =
                cashFlowHttpClient.getCashFlowInfo(cashFlowId, authToken);

        // Validate category exists
        boolean isInflow = command.baseAmount().isPositive();
        validateCategory(cashFlowInfo, command.categoryName(), isInflow, cashFlowId);

        // Validate date range
        if (command.endDate() != null && command.startDate().isAfter(command.endDate())) {
            throw new InvalidDateRangeException(command.startDate(), command.endDate());
        }

        // Validate maxOccurrences
        if (command.maxOccurrences() != null && command.maxOccurrences() <= 0) {
            throw new IllegalArgumentException("maxOccurrences must be positive");
        }

        // Generate ID and create rule
        long sequence = ruleRepository.generateNextSequence();
        RecurringRuleId ruleId = RecurringRuleId.generate(sequence);

        RecurringRule rule = RecurringRule.create(
                ruleId,
                UserId.of(command.userId()),
                cashFlowId,
                command.name(),
                command.description(),
                command.baseAmount(),
                command.categoryName(),
                command.pattern(),
                command.startDate(),
                command.endDate(),
                command.maxOccurrences(),
                command.activeMonths(),
                command.excludedDates(),
                clock
        );

        ruleRepository.save(rule);

        // Generate expected cash changes
        generateExpectedCashChanges(rule, authToken);

        log.info("Created recurring rule {} for CashFlow {}", ruleId.id(), cashFlowId.id());
        return ruleId;
    }


    public void handle(UpdateRuleCommand command, String authToken) throws RecurringRuleException {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (rule.isDeleted()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "update");
        }

        CashFlowId cashFlowId = rule.getCashFlowId();
        CashFlowHttpClient.CashFlowInfo cashFlowInfo =
                cashFlowHttpClient.getCashFlowInfo(cashFlowId, authToken);

        boolean isInflow = command.baseAmount().isPositive();
        validateCategory(cashFlowInfo, command.categoryName(), isInflow, cashFlowId);

        if (command.endDate() != null && command.startDate().isAfter(command.endDate())) {
            throw new InvalidDateRangeException(command.startDate(), command.endDate());
        }

        // Validate maxOccurrences
        if (command.maxOccurrences() != null && command.maxOccurrences() <= 0) {
            throw new IllegalArgumentException("maxOccurrences must be positive");
        }

        // Clear old expected cash changes
        clearGeneratedCashChanges(rule, authToken);

        // Update rule
        rule.update(
                command.name(),
                command.description(),
                command.baseAmount(),
                command.categoryName(),
                command.pattern(),
                command.startDate(),
                command.endDate(),
                command.maxOccurrences(),
                command.activeMonths(),
                command.excludedDates(),
                clock
        );

        ruleRepository.save(rule);

        // Generate new expected cash changes
        if (rule.isActive()) {
            generateExpectedCashChanges(rule, authToken);
        }

        log.info("Updated recurring rule {}", ruleId.id());
    }


    public void handle(PauseRuleCommand command, String authToken) throws RecurringRuleException {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (!rule.isActive()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "pause");
        }

        // Clear pending cash changes before pausing - paused rule should not have future forecasts
        clearGeneratedCashChanges(rule, authToken);

        PauseInfo pauseInfo = command.resumeDate() != null
                ? PauseInfo.untilDate(clock.instant(), command.resumeDate(), command.reason())
                : PauseInfo.indefinite(clock.instant(), command.reason());

        rule.pause(pauseInfo, clock);
        ruleRepository.save(rule);

        log.info("Paused recurring rule {} until {} (cleared pending cash changes)", ruleId.id(),
                command.resumeDate() != null ? command.resumeDate() : "indefinitely");
    }


    public void handle(ResumeRuleCommand command, String authToken) throws RecurringRuleException {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (!rule.isPaused()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "resume");
        }

        rule.resume(clock);
        ruleRepository.save(rule);

        // Defensive: clear any remaining pending cash changes (edge case - should be empty after proper pause)
        clearGeneratedCashChanges(rule, authToken);
        // Generate new expected cash changes from today
        generateExpectedCashChanges(rule, authToken);

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

    public void handle(DeleteRuleCommand command, String authToken) throws RecurringRuleException {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (rule.isDeleted()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "delete");
        }

        // Clear generated cash changes
        clearGeneratedCashChanges(rule, authToken);

        rule.delete(command.reason(), clock);
        ruleRepository.save(rule);

        log.info("Deleted recurring rule {}", ruleId.id());
    }


    public void handle(GenerateExpectedCashChangesCommand command, String authToken) throws RecurringRuleException {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = findRuleOrThrow(ruleId);

        if (!rule.isActive()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "generate expected cash changes");
        }

        // Clear old and generate new
        clearGeneratedCashChanges(rule, authToken);
        generateExpectedCashChanges(rule, authToken);
    }

    public AmountChangeId handle(AddAmountChangeCommand command, String authToken) throws RecurringRuleException {
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
            clearGeneratedCashChanges(rule, authToken);
            generateExpectedCashChanges(rule, authToken);
        }

        log.info("Added {} amount change {} to rule {}", command.type(), changeId.id(), ruleId.id());
        return changeId;
    }

    public void handle(RemoveAmountChangeCommand command, String authToken) throws RecurringRuleException {
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
            clearGeneratedCashChanges(rule, authToken);
            generateExpectedCashChanges(rule, authToken);
        }

        log.info("Removed amount change {} from rule {}", changeId.id(), ruleId.id());
    }

    // Query Handlers


    public RecurringRuleSnapshot handle(GetRuleQuery query) throws RuleNotFoundException {
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

    public DeleteImpactPreviewResponse handle(PreviewDeleteImpactQuery query) throws RecurringRuleException {
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

    // Private helper methods

    private RecurringRule findRuleOrThrow(RecurringRuleId ruleId) throws RuleNotFoundException {
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));
    }

    private void validateCategory(
            CashFlowHttpClient.CashFlowInfo cashFlowInfo,
            CategoryName categoryName,
            boolean isInflow,
            CashFlowId cashFlowId
    ) throws CategoryNotFoundException {
        List<CategoryName> categories = isInflow
                ? cashFlowInfo.inflowCategories()
                : cashFlowInfo.outflowCategories();

        boolean exists = categories.stream()
                .anyMatch(cat -> cat.name().equals(categoryName.name()));

        if (!exists) {
            throw new CategoryNotFoundException(cashFlowId, categoryName);
        }
    }

    private void generateExpectedCashChanges(RecurringRule rule, String authToken)
            throws CashFlowCommunicationException {
        LocalDate fromDate = LocalDate.now(clock);
        YearMonth currentMonth = YearMonth.from(fromDate);
        LocalDate toDate = currentMonth.plusMonths(FORECAST_MONTHS - 1).atEndOfMonth();

        List<LocalDate> occurrences = rule.generateOccurrences(fromDate, toDate);
        List<CashChangeId> generatedIds = new ArrayList<>();

        String type = rule.getBaseAmount().isPositive() ? "INFLOW" : "OUTFLOW";

        for (LocalDate occurrence : occurrences) {
            try {
                Money effectiveAmount = rule.calculateEffectiveAmount(occurrence);
                Money absAmount = effectiveAmount.isPositive()
                        ? effectiveAmount
                        : Money.of(effectiveAmount.getAmount().negate(), effectiveAmount.getCurrency());

                ZonedDateTime dueDate = occurrence.atStartOfDay(ZoneOffset.UTC);

                CashChangeId cashChangeId = cashFlowHttpClient.createExpectedCashChange(
                        rule.getCashFlowId(),
                        rule.getRuleId(),
                        rule.getCategoryName(),
                        rule.getName(),
                        rule.getDescription(),
                        absAmount,
                        type,
                        dueDate,
                        authToken
                );

                generatedIds.add(cashChangeId);
                log.debug("Created expected cash change {} for rule {} on {}",
                        cashChangeId.id(), rule.getRuleId().id(), occurrence);
            } catch (CashFlowCommunicationException e) {
                log.error("Failed to create expected cash change for rule {} on {}: {}",
                        rule.getRuleId().id(), occurrence, e.getMessage());
                throw e;
            }
        }

        if (!generatedIds.isEmpty()) {
            rule.recordGeneratedCashChanges(generatedIds, fromDate, toDate, clock);
            ruleRepository.save(rule);
            log.info("Generated {} expected cash changes for rule {}", generatedIds.size(), rule.getRuleId().id());

            // Check if rule should auto-complete after reaching maxOccurrences
            if (rule.shouldAutoComplete()) {
                rule.complete("Reached maximum occurrences (" + rule.getMaxOccurrences().orElse(0) + ")", clock);
                ruleRepository.save(rule);
                log.info("Rule {} auto-completed after reaching {} occurrences",
                        rule.getRuleId().id(), rule.getMaxOccurrences().orElse(0));
            }
        }
    }

    private void clearGeneratedCashChanges(RecurringRule rule, String authToken)
            throws CashFlowCommunicationException {
        List<CashChangeId> toDelete = rule.getGeneratedCashChangeIds();

        if (toDelete.isEmpty()) {
            return;
        }

        CashFlowHttpClient.BatchDeleteResult result = cashFlowHttpClient.batchDeleteExpectedCashChanges(
                rule.getCashFlowId(),
                rule.getRuleId(),
                toDelete,
                authToken
        );

        log.info("Batch deleted {} cash changes for rule {} (skipped {} confirmed)",
                result.deletedCount(), rule.getRuleId().id(), result.skippedCount());

        rule.clearGeneratedCashChanges(toDelete, clock);
        ruleRepository.save(rule);
    }
}
