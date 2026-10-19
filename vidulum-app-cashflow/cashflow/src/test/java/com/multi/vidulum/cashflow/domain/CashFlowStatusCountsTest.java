package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Counting what one source rule put into a cash flow, by state.
 *
 * <p>This is where the arithmetic lives because two callers need the same answer - the module
 * contract and the endpoint its HTTP transport calls - and a cash flow is what knows which of its
 * cash changes came from where.
 *
 * <p>The consumer this replaced compared {@code "PENDING"} and {@code "CONFIRMED"} as string
 * literals against a {@code Map<String, Object>}, so a rejected or archived cash change counted as
 * neither and simply disappeared from the total. That is what {@code other} is for, and the third
 * case below is the one that would have caught it.
 */
class CashFlowStatusCountsTest {

    private static final String RULE = "RR00000001";
    private static final String ANOTHER_RULE = "RR00000002";

    @Test
    void countsOnlyWhatTheNamedRuleProduced() {
        CashFlow cashFlow = cashFlowHolding(
                cashChange("CC1000000001", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000002", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000003", CashChangeStatus.PENDING, ANOTHER_RULE),
                cashChange("CC1000000004", CashChangeStatus.PENDING, null));

        assertThat(cashFlow.countBySourceRule(RULE))
                .isEqualTo(new CashFlow.StatusCounts(2, 0, 0));
    }

    @Test
    void separatesWhatIsStillAPredictionFromWhatBecameAFact() {
        CashFlow cashFlow = cashFlowHolding(
                cashChange("CC1000000001", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000002", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000003", CashChangeStatus.CONFIRMED, RULE));

        assertThat(cashFlow.countBySourceRule(RULE))
                .isEqualTo(new CashFlow.StatusCounts(2, 1, 0));
    }

    /**
     * The case the string comparison lost. A cash flow knows four states; a consumer counting two of
     * them produced a total that did not add up and said nothing about why.
     */
    @Test
    void aRejectedOrArchivedCashChangeIsCountedRatherThanDropped() {
        CashFlow cashFlow = cashFlowHolding(
                cashChange("CC1000000001", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000002", CashChangeStatus.CONFIRMED, RULE),
                cashChange("CC1000000003", CashChangeStatus.REJECTED, RULE),
                cashChange("CC1000000004", CashChangeStatus.ARCHIVED, RULE));

        CashFlow.StatusCounts counts = cashFlow.countBySourceRule(RULE);

        assertThat(counts).isEqualTo(new CashFlow.StatusCounts(1, 1, 2));
        assertThat(counts.pending() + counts.confirmed() + counts.other())
                .as("and the total adds up, which is the whole point of the third number")
                .isEqualTo(4);
    }

    @Test
    void anUnknownRuleOwnsNothing() {
        CashFlow cashFlow = cashFlowHolding(cashChange("CC1000000001", CashChangeStatus.PENDING, RULE));

        assertThat(cashFlow.countBySourceRule("RR09999999"))
                .isEqualTo(new CashFlow.StatusCounts(0, 0, 0));
    }

    /**
     * Not an exception: a caller with nothing to ask about gets zeros. The cash changes a person
     * entered by hand carry no source rule, and they are nobody's to count here.
     */
    @Test
    void noRuleAtAllCountsNothingRatherThanEverything() {
        CashFlow cashFlow = cashFlowHolding(
                cashChange("CC1000000001", CashChangeStatus.PENDING, null),
                cashChange("CC1000000002", CashChangeStatus.CONFIRMED, null));

        assertThat(cashFlow.countBySourceRule(null))
                .isEqualTo(new CashFlow.StatusCounts(0, 0, 0));
    }

    private static CashFlow cashFlowHolding(CashChangeSnapshot... cashChanges) {
        Map<CashChangeId, CashChangeSnapshot> byId = new LinkedHashMap<>();
        for (CashChangeSnapshot cashChange : cashChanges) {
            byId.put(cashChange.cashChangeId(), cashChange);
        }
        return CashFlow.from(new CashFlowSnapshot(
                CashFlowId.of("CF10000001"),
                UserId.of("U10000001"),
                new Name("Nest Bank"),
                new Description("current account"),
                BankAccount.fromIban("Nest Bank", "PL61109010140000071219812874",
                        Currency.of("PLN"), Money.of(0, "PLN"), null),
                CashFlow.CashFlowStatus.OPEN,
                byId,
                YearMonth.of(2022, 1),
                YearMonth.of(2022, 1),
                Money.of(0, "PLN"),
                List.of(),
                List.of(),
                ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                null,
                new Checksum("irrelevant-here"),
                null));
    }

    private static CashChangeSnapshot cashChange(String id, CashChangeStatus status, String sourceRuleId) {
        return new CashChangeSnapshot(
                CashChangeId.of(id),
                new Name("Rent"),
                new Description("generated"),
                Money.of(1500, "PLN"),
                Type.OUTFLOW,
                new CategoryName("Housing"),
                status,
                ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                ZonedDateTime.parse("2022-01-15T00:00:00Z"),
                null,
                sourceRuleId,
                false,
                null);
    }
}
