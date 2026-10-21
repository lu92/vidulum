package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.DeletedExpectedCashChanges;
import com.multi.vidulum.cashflow.api.ExpectedCashChange;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.ForecastWindow;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Runs a rule: turns it into the expected cash changes it implies, and takes them back out again.
 *
 * <p>A rule on its own changes nothing - it is a pattern plus an amount. What a person sees in their
 * forecast is what this class wrote there, which is why {@code generate} and {@code clear} belong
 * together: every change to a rule is a clear followed by a generate.
 *
 * <p>Lifted out of {@code RecurringRuleService} - a class that no longer exists - because
 * {@code generate} had six callers there and {@code clear} seven, and every handler that left needed
 * them. Two sides of one job on the same three collaborators, so one component rather than two.
 *
 * <p>Public only so the command handlers in {@code app.commands..} can reach it;
 * {@code ModuleBoundariesArchTest} is what keeps {@code recurring_rules.app..} closed to other
 * modules.
 *
 * <p><b>No HTTP client here any more, and no {@code authToken} in either signature.</b> Both halves
 * of the job - writing a rule's predictions and taking them back - go through
 * {@link CashFlowModuleApi}, one call each. The module's own hand-written client is gone entirely
 * (F60), which is what closed task F26.
 */
@Slf4j
@Component
@AllArgsConstructor
public class RecurringRuleExecutor {

    private final DomainRecurringRuleRepository ruleRepository;
    private final CashFlowModuleApi cashFlowApi;
    private final Clock clock;

    /**
     * Writes every occurrence the rule implies inside the forecast window - in one call.
     *
     * <p>No {@code authToken}, and that is the visible half of the change: this used to be a loop
     * calling the module's own HTTP client once per occurrence, so a daily rule made 365 requests,
     * each with its own aggregate load, whole-document save, id reservation and topic message. It
     * now asks {@link CashFlowModuleApi#appendExpected}, which does all of it once.
     *
     * <p>The invisible half matters more. The loop was not atomic: failing on occurrence 200 left
     * 199 cash changes in the cash flow while {@code recordGeneratedCashChanges} below was never
     * reached, so the rule knew none of their ids - and {@link #clear} works from exactly that list,
     * which means nothing could ever remove them. The batch either lands whole or changes nothing.
     */
    public void generate(RecurringRule rule) {
        // read once: the window the occurrences are generated for, the stamp on the rule and the
        // stamp on an auto-completion are one moment, where three readings could straddle midnight
        Instant now = clock.instant();
        ForecastWindow window = ForecastWindow.startingAt(LocalDate.ofInstant(now, clock.getZone()));

        List<LocalDate> occurrences = rule.generateOccurrences(window.from(), window.to());
        if (occurrences.isEmpty()) {
            return;
        }

        Type type = rule.getBaseAmount().isPositive() ? Type.INFLOW : Type.OUTFLOW;

        List<ExpectedCashChange> changes = occurrences.stream()
                .map(occurrence -> new ExpectedCashChange(
                        rule.getCategoryName(),
                        rule.getName(),
                        rule.getDescription(),
                        amountDueAt(rule, occurrence),
                        type,
                        occurrence.atStartOfDay(ZoneOffset.UTC)))
                .toList();

        List<CashChangeId> generatedIds = cashFlowApi.appendExpected(
                rule.getCashFlowId(), rule.getRuleId().id(), changes);

        rule.recordGeneratedCashChanges(generatedIds, window.from(), window.to(), now);
        ruleRepository.save(rule);
        log.info("Generated {} expected cash changes for rule {}", generatedIds.size(), rule.getRuleId().id());

        // Check if rule should auto-complete after reaching maxOccurrences
        if (rule.shouldAutoComplete()) {
            rule.complete(STR."Reached maximum occurrences (\{rule.getMaxOccurrences().orElse(0)})", now);
            ruleRepository.save(rule);
            log.info("Rule {} auto-completed after reaching {} occurrences",
                    rule.getRuleId().id(), rule.getMaxOccurrences().orElse(0));
        }
    }

    /**
     * What a rule asks for at one occurrence, as a positive amount: the sign lives in the
     * {@link Type}, so an outflow of 1500 is 1500 OUTFLOW rather than -1500.
     */
    private static Money amountDueAt(RecurringRule rule, LocalDate occurrence) {
        Money effectiveAmount = rule.calculateEffectiveAmount(occurrence);
        return effectiveAmount.isPositive()
                ? effectiveAmount
                : Money.of(effectiveAmount.getAmount().negate(), effectiveAmount.getCurrency());
    }

    /**
     * Takes back everything this rule had predicted, in one call.
     *
     * <p>Unlike {@link #generate}, which refuses a whole batch rather than drop an item, cashflow
     * <b>skips</b> here - a cash change the owner has already confirmed is a fact about their money,
     * not a prediction, so it stays. The rule stops tracking it either way: it is the owner's now.
     */
    public void clear(RecurringRule rule) {
        List<CashChangeId> toDelete = rule.getGeneratedCashChangeIds();

        if (toDelete.isEmpty()) {
            return;
        }

        DeletedExpectedCashChanges result = cashFlowApi.deleteExpected(
                rule.getCashFlowId(), rule.getRuleId().id(), toDelete);

        log.info("Withdrew {} expected cash changes for rule {} ({} were already confirmed and stay)",
                result.deleted(), rule.getRuleId().id(), result.skipped());

        rule.clearGeneratedCashChanges(toDelete, clock.instant());
        ruleRepository.save(rule);
    }
}
