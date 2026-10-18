package com.multi.vidulum.cashflow.api.forecast;

import com.multi.vidulum.common.CashFlowId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.YearMonth;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule that decides which months accept an import.
 *
 * <p>Worth a test of its own because until this type existed the rule could not be exercised at
 * all. It lived in {@code bank-data-ingestion}'s own copy of the status vocabulary, fed by a test
 * double that invented statuses from a heuristic and could not produce {@code ATTESTED} - so the
 * refusal below had no test, and no way to get one (task F38).
 */
class MonthStatusesTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    /**
     * The two sets are spelled out rather than derived, so that this test disagrees with a changed
     * rule instead of following it. {@link #everyStatusIsClassified()} is what makes them total.
     */
    private static final Set<MonthStatus> WRITABLE =
            EnumSet.of(MonthStatus.IMPORT_PENDING, MonthStatus.IMPORTED, MonthStatus.ROLLED_OVER, MonthStatus.ACTIVE);
    private static final Set<MonthStatus> READ_ONLY =
            EnumSet.of(MonthStatus.FORECASTED, MonthStatus.ATTESTED);

    @ParameterizedTest
    @EnumSource(MonthStatus.class)
    void aMonthAcceptsAnImportExactlyWhenItIsStillWritable(MonthStatus status) {
        MonthStatuses statuses = new MonthStatuses(CASH_FLOW_ID, Map.of(MARCH, status));

        assertThat(statuses.acceptsImportAt(MARCH))
                .as("%s", status)
                .isEqualTo(WRITABLE.contains(status));
    }

    @Test
    void anAttestedMonthIsReadOnly() {
        MonthStatuses statuses = new MonthStatuses(CASH_FLOW_ID, Map.of(MARCH, MonthStatus.ATTESTED));

        assertThat(statuses.at(MARCH)).contains(MonthStatus.ATTESTED);
        assertThat(statuses.acceptsImportAt(MARCH)).isFalse();
    }

    @Test
    void everyStatusIsClassified() {
        assertThat(EnumSet.allOf(MonthStatus.class))
                .as("a new month status must be decided here, not inherit a default")
                .containsExactlyInAnyOrderElementsOf(EnumSet.copyOf(union()));
        assertThat(WRITABLE).doesNotContainAnyElementsOf(READ_ONLY);
    }

    @Test
    void aMonthTheForecastDoesNotKnowIsRefusedRatherThanAssumed() {
        MonthStatuses statuses = new MonthStatuses(CASH_FLOW_ID, Map.of(MARCH, MonthStatus.ACTIVE));

        assertThat(statuses.at(YearMonth.of(2026, 4))).isEmpty();
        assertThat(statuses.acceptsImportAt(YearMonth.of(2026, 4))).isFalse();
    }

    @Test
    void noForecastYetIsNotAnError() {
        MonthStatuses none = MonthStatuses.none(CASH_FLOW_ID);

        assertThat(none.cashFlowId()).isEqualTo(CASH_FLOW_ID);
        assertThat(none.isEmpty()).isTrue();
        assertThat(none.byMonth()).isEmpty();
        assertThat(none.acceptsImportAt(MARCH)).isFalse();
    }

    @Test
    void aMissingMapIsTheSameAsNoMonths() {
        MonthStatuses statuses = new MonthStatuses(CASH_FLOW_ID, null);

        assertThat(statuses.isEmpty()).isTrue();
        assertThat(statuses.acceptsImportAt(MARCH)).isFalse();
    }

    @Test
    void theMonthsAreCopiedOnTheWayInAndCannotBeChangedAfterwards() {
        Map<YearMonth, MonthStatus> mutable = new HashMap<>(Map.of(MARCH, MonthStatus.ACTIVE));
        MonthStatuses statuses = new MonthStatuses(CASH_FLOW_ID, mutable);

        mutable.put(MARCH, MonthStatus.ATTESTED);
        mutable.put(YearMonth.of(2026, 4), MonthStatus.FORECASTED);

        assertThat(statuses.at(MARCH)).contains(MonthStatus.ACTIVE);
        assertThat(statuses.byMonth()).hasSize(1);
        assertThatThrownBy(() -> statuses.byMonth().put(MARCH, MonthStatus.ATTESTED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Set<MonthStatus> union() {
        Set<MonthStatus> all = EnumSet.copyOf(WRITABLE);
        all.addAll(READ_ONLY);
        return all;
    }
}
