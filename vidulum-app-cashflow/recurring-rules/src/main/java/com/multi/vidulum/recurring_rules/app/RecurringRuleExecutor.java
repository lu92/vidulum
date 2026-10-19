package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.exceptions.CashFlowCommunicationException;
import com.multi.vidulum.recurring_rules.infrastructure.CashFlowHttpClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a rule: turns it into the expected cash changes it implies, and takes them back out again.
 *
 * <p>A rule on its own changes nothing - it is a pattern plus an amount. What a person sees in their
 * forecast is what this class wrote there, which is why {@code generate} and {@code clear} belong
 * together: every change to a rule is a clear followed by a generate.
 *
 * <p>Lifted out of {@code RecurringRuleService} unchanged, because the two command handlers that
 * left that class need it and so do five of the methods that stayed - {@code generate} had six
 * callers and {@code clear} seven. Two sides of one job on the same three collaborators, so one
 * component rather than two.
 *
 * <p>Public only so the command handlers in {@code app.commands..} can reach it;
 * {@code ModuleBoundariesArchTest} is what keeps {@code recurring_rules.app..} closed to other
 * modules. The {@code authToken} is here for the same reason it is everywhere else in this module -
 * {@link CashFlowHttpClient} still writes over HTTP, which is task F26.
 */
@Slf4j
@Component
@AllArgsConstructor
public class RecurringRuleExecutor {

    private static final int FORECAST_MONTHS = 12; // Current + 11 months

    private final DomainRecurringRuleRepository ruleRepository;
    private final CashFlowHttpClient cashFlowHttpClient;
    private final Clock clock;

    public void generate(RecurringRule rule, String authToken) {
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

    public void clear(RecurringRule rule, String authToken) {
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
