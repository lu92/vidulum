package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.PauseInfo;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The version has to survive entity → snapshot → aggregate → snapshot → entity, because
 * {@code save} rebuilds the entity from the aggregate's snapshot. Lose it on that path and it
 * reaches Mongo as null, which Spring Data reads as "new document" - so the update becomes an
 * insert.
 *
 * <p><b>Written because the compiler only caught half of it.</b> {@code RecurringRuleSnapshot} is a
 * record, so adding a component broke both {@code new RecurringRuleSnapshot(...)} sites loudly - but
 * {@link RecurringRuleEntity#fromSnapshot} builds through a Lombok builder, which compiled clean
 * while silently dropping the value. One mapping path checked by the compiler, the other only by
 * this test.
 */
class RecurringRuleVersionRoundTripTest {

    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");

    @Test
    void theStoredVersionSurvivesTheWholeWayBackToTheEntity() {
        RecurringRuleEntity stored = RecurringRuleEntity.fromSnapshot(ruleWithVersion(4L));

        assertThat(stored.getVersion()).isEqualTo(4L);

        RecurringRule aggregate = RecurringRule.fromSnapshot(stored.toSnapshot());
        assertThat(aggregate.getSnapshot().version())
                .as("read back through the aggregate")
                .isEqualTo(4L);

        assertThat(RecurringRuleEntity.fromSnapshot(aggregate.getSnapshot()).getVersion())
                .as("and on to the entity the next save writes")
                .isEqualTo(4L);
    }

    @Test
    void aRuleThatWasNeverStoredCarriesNoVersion() {
        assertThat(RecurringRuleEntity.fromSnapshot(ruleWithVersion(null)).getVersion())
                .as("null is what tells Spring Data to insert")
                .isNull();
    }

    /**
     * A rule changed by its owner and a rule changed by the scheduler take the same path through
     * storage, so a state change must not drop the version either.
     */
    @Test
    void pausingTheRuleKeepsTheVersionItWasReadAt() {
        RecurringRule aggregate = RecurringRule.fromSnapshot(ruleWithVersion(9L));

        aggregate.pause(PauseInfo.indefinite(NOW, "holiday"), NOW);

        assertThat(aggregate.getSnapshot().version()).isEqualTo(9L);
    }

    private static RecurringRuleSnapshot ruleWithVersion(Long version) {
        RecurringRule rule = RecurringRule.create(
                RecurringRuleId.generate(1),
                UserId.of("U10000001"),
                CashFlowId.of("CF10000001"),
                "Rent",
                "monthly rent",
                Money.of(-1500, "PLN"),
                new CategoryName("Housing"),
                new MonthlyPattern(15, 1, false),
                LocalDate.of(2022, 1, 1),
                null, null, null, null,
                NOW);

        RecurringRuleSnapshot fresh = rule.getSnapshot();
        return new RecurringRuleSnapshot(
                fresh.ruleId(), fresh.userId(), fresh.cashFlowId(), fresh.name(), fresh.description(),
                fresh.baseAmount(), fresh.categoryName(), fresh.pattern(), fresh.startDate(),
                fresh.endDate(), fresh.maxOccurrences(), fresh.activeMonths(), fresh.excludedDates(),
                fresh.status(), fresh.pauseInfo(), fresh.amountChanges(), fresh.executions(),
                fresh.generatedCashChangeIds(), fresh.createdAt(), fresh.lastModifiedAt(),
                version);
    }
}
