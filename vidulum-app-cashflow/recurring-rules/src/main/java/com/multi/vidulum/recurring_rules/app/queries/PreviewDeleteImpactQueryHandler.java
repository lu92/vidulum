package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.cashflow.api.CashChangeCountsByStatus;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.app.dto.DeleteImpactPreviewResponse;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.ForecastWindow;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * What deleting a rule would cost: the predictions that leave the forecast, the transactions that
 * stay, and the months that change.
 *
 * <p>It reads {@link ForecastWindow} rather than computing twelve months of its own, because it is
 * previewing <b>what {@code RecurringRuleExecutor} generated</b> - a window of its own would make
 * the two agree only by coincidence.
 *
 * <p>The counts come from {@code CashFlowModuleApi.countCashChangesByStatus}: cashflow owns the
 * state of a cash change, and a confirmed one is a fact about somebody's money that deleting a rule
 * does not touch. Hence {@code deletable == pending}, which is the number this screen exists to
 * show.
 */
@Component
@AllArgsConstructor
public class PreviewDeleteImpactQueryHandler
        implements QueryHandler<PreviewDeleteImpactQuery, DeleteImpactPreviewResponse> {

    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("10000");
    private static final int MANY_OCCURRENCES = 3;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DomainRecurringRuleRepository ruleRepository;
    private final CashFlowModuleApi cashFlowApi;
    private final Clock clock;

    @Override
    public DeleteImpactPreviewResponse query(PreviewDeleteImpactQuery query) {
        RecurringRuleId ruleId = RecurringRuleId.of(query.ruleId());
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));

        ForecastWindow window = ForecastWindow.startingAt(LocalDate.now(clock));

        // only an active rule has anything in the forecast to lose
        List<LocalDate> futureOccurrences = rule.isActive()
                ? rule.generateOccurrences(window.from(), window.to())
                : List.of();

        String currency = rule.getBaseAmount().getCurrency();
        BigDecimal totalFutureAmount = totalOf(rule, futureOccurrences);

        CashChangeCountsByStatus counts = countsOf(rule, ruleId);

        return DeleteImpactPreviewResponse.builder()
                .ruleId(ruleId.id())
                .ruleName(rule.getName())
                .impact(DeleteImpactPreviewResponse.ImpactDetails.builder()
                        .futureOccurrences(futureOccurrencesOf(futureOccurrences, totalFutureAmount, currency))
                        .generatedTransactions(generatedTransactionsOf(rule, counts))
                        .forecastImpact(forecastImpactOf(futureOccurrences, totalFutureAmount, currency))
                        .build())
                .warnings(warningsFor(counts.confirmed(), totalFutureAmount, currency))
                .recommendations(recommendationsFor(rule, futureOccurrences.size(), counts.confirmed()))
                .build();
    }

    /**
     * Asked only when the rule recorded cash changes - a rule that generated nothing has nothing to
     * count, and asking anyway would be a call per preview for a guaranteed zero.
     */
    private CashChangeCountsByStatus countsOf(RecurringRule rule, RecurringRuleId ruleId) {
        List<CashChangeId> generatedIds = rule.getGeneratedCashChangeIds();
        return generatedIds.isEmpty()
                ? new CashChangeCountsByStatus(0, 0, 0)
                : cashFlowApi.countCashChangesByStatus(rule.getCashFlowId(), ruleId.id());
    }

    /**
     * What the rule would have asked for, as a positive total: the sign lives in the type, and a
     * preview adds up how much is at stake rather than which way it flows.
     */
    private static BigDecimal totalOf(RecurringRule rule, List<LocalDate> occurrences) {
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate occurrence : occurrences) {
            total = total.add(rule.calculateEffectiveAmount(occurrence).getAmount().abs());
        }
        return total;
    }

    private static DeleteImpactPreviewResponse.FutureOccurrences futureOccurrencesOf(
            List<LocalDate> occurrences, BigDecimal totalAmount, String currency) {
        DeleteImpactPreviewResponse.DateRange dateRange = occurrences.isEmpty()
                ? null
                : DeleteImpactPreviewResponse.DateRange.builder()
                        .from(occurrences.getFirst())
                        .to(occurrences.getLast())
                        .build();

        return DeleteImpactPreviewResponse.FutureOccurrences.builder()
                .count(occurrences.size())
                .totalAmount(Money.of(totalAmount, currency))
                .dateRange(dateRange)
                .build();
    }

    private static DeleteImpactPreviewResponse.GeneratedTransactions generatedTransactionsOf(
            RecurringRule rule, CashChangeCountsByStatus counts) {
        return DeleteImpactPreviewResponse.GeneratedTransactions.builder()
                .total(rule.getGeneratedCashChangeIds().size())
                .pending(counts.pending())
                .confirmed(counts.confirmed())
                .deletable(counts.pending())
                .build();
    }

    private static DeleteImpactPreviewResponse.ForecastImpact forecastImpactOf(
            List<LocalDate> occurrences, BigDecimal totalAmount, String currency) {
        List<String> affectedMonths = occurrences.stream()
                .map(date -> YearMonth.from(date).format(MONTH))
                .distinct()
                .sorted()
                .toList();

        return DeleteImpactPreviewResponse.ForecastImpact.builder()
                .affectedMonths(affectedMonths)
                .balanceReduction(Money.of(totalAmount, currency))
                .build();
    }

    private static List<DeleteImpactPreviewResponse.Warning> warningsFor(
            int confirmedCount, BigDecimal totalFutureAmount, String currency) {
        List<DeleteImpactPreviewResponse.Warning> warnings = new ArrayList<>();

        if (confirmedCount > 0) {
            warnings.add(DeleteImpactPreviewResponse.Warning.builder()
                    .type("CONFIRMED_TRANSACTIONS")
                    .message(String.format("%d potwierdzone transakcje pozostaną bez zmian", confirmedCount))
                    .severity("INFO")
                    .build());
        }

        if (totalFutureAmount.compareTo(HIGH_VALUE_THRESHOLD) > 0) {
            warnings.add(DeleteImpactPreviewResponse.Warning.builder()
                    .type("HIGH_VALUE")
                    .message(String.format("Ta reguła generuje transakcje o łącznej wartości %.2f %s",
                            totalFutureAmount, currency))
                    .severity("WARNING")
                    .build());
        }

        return warnings;
    }

    private static List<String> recommendationsFor(RecurringRule rule, int futureCount, int confirmedCount) {
        List<String> recommendations = new ArrayList<>();

        if (rule.isActive() && futureCount > MANY_OCCURRENCES) {
            recommendations.add("Rozważ wstrzymanie reguły zamiast usuwania, jeśli planujesz ją reaktywować w przyszłości");
        }

        if (confirmedCount > 0) {
            recommendations.add("Potwierdzone transakcje pozostaną w CashFlow jako historyczne dane");
        }

        return recommendations;
    }
}
