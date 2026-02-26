package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.commands.*;
import com.multi.vidulum.recurring_rules.app.queries.*;
import com.multi.vidulum.recurring_rules.domain.*;
import com.multi.vidulum.recurring_rules.domain.exceptions.*;
import com.multi.vidulum.recurring_rules.infrastructure.CashFlowHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

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

        PauseInfo pauseInfo = command.resumeDate() != null
                ? PauseInfo.untilDate(clock.instant(), command.resumeDate(), command.reason())
                : PauseInfo.indefinite(clock.instant(), command.reason());

        rule.pause(pauseInfo, clock);
        ruleRepository.save(rule);

        log.info("Paused recurring rule {} until {}", ruleId.id(),
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

        // Regenerate expected cash changes
        generateExpectedCashChanges(rule, authToken);

        log.info("Resumed recurring rule {}", ruleId.id());
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
        }
    }

    private void clearGeneratedCashChanges(RecurringRule rule, String authToken) {
        List<CashChangeId> toDelete = rule.getGeneratedCashChangeIds();

        for (CashChangeId cashChangeId : toDelete) {
            try {
                cashFlowHttpClient.deleteExpectedCashChange(rule.getCashFlowId(), cashChangeId, authToken);
                log.debug("Deleted expected cash change {}", cashChangeId.id());
            } catch (CashFlowCommunicationException e) {
                log.warn("Failed to delete expected cash change {}: {}", cashChangeId.id(), e.getMessage());
            }
        }

        if (!toDelete.isEmpty()) {
            rule.clearGeneratedCashChanges(toDelete, clock);
            ruleRepository.save(rule);
        }
    }
}
