package com.multi.vidulum.cashflow.app.queries;

import com.multi.vidulum.cashflow.api.ExpectedCashChangesByStatus;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.CashChangeStatus;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Counting what one source rule put into a cash flow, by state.
 *
 * <p>A query handler rather than a method on {@code CashFlow}: nothing is being decided here, and
 * the aggregate's job is the rules about changing a cash flow, not reporting on one. Two callers
 * need the same answer - {@code CashFlowModuleApi.expectedCashChangesOf} and the endpoint its HTTP
 * transport calls - and both send this query, so the counting exists once.
 *
 * <p>The consumer this replaced compared {@code "PENDING"} and {@code "CONFIRMED"} as string
 * literals against a {@code Map<String, Object>} it had fetched the whole cash flow to get, so a
 * rejected or archived cash change counted as neither and simply disappeared from the total. That
 * is what {@code other} is for, and the third case below is the one that would have caught it.
 */
class GetExpectedCashChangesByStatusQueryHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final String RULE = "RR00000001";
    private static final String ANOTHER_RULE = "RR00000002";

    @Test
    void countsOnlyWhatTheNamedRuleProduced() {
        ExpectedCashChangesByStatus counted = count(RULE,
                cashChange("CC1000000001", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000002", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000003", CashChangeStatus.PENDING, ANOTHER_RULE),
                cashChange("CC1000000004", CashChangeStatus.PENDING, null));

        assertThat(counted).isEqualTo(new ExpectedCashChangesByStatus(2, 0, 0));
    }

    @Test
    void separatesWhatIsStillAPredictionFromWhatBecameAFact() {
        ExpectedCashChangesByStatus counted = count(RULE,
                cashChange("CC1000000001", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000002", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000003", CashChangeStatus.CONFIRMED, RULE));

        assertThat(counted).isEqualTo(new ExpectedCashChangesByStatus(2, 1, 0));
    }

    /**
     * The case the string comparison lost. A cash flow knows four states; a consumer counting two of
     * them produced a total that did not add up and said nothing about why.
     */
    @Test
    void aRejectedOrArchivedCashChangeIsCountedRatherThanDropped() {
        ExpectedCashChangesByStatus counted = count(RULE,
                cashChange("CC1000000001", CashChangeStatus.PENDING, RULE),
                cashChange("CC1000000002", CashChangeStatus.CONFIRMED, RULE),
                cashChange("CC1000000003", CashChangeStatus.REJECTED, RULE),
                cashChange("CC1000000004", CashChangeStatus.ARCHIVED, RULE));

        assertThat(counted).isEqualTo(new ExpectedCashChangesByStatus(1, 1, 2));
        assertThat(counted.total())
                .as("and the total adds up, which is the whole point of the third number")
                .isEqualTo(4);
    }

    @Test
    void anUnknownRuleOwnsNothing() {
        ExpectedCashChangesByStatus counted = count("RR09999999",
                cashChange("CC1000000001", CashChangeStatus.PENDING, RULE));

        assertThat(counted).isEqualTo(new ExpectedCashChangesByStatus(0, 0, 0));
    }

    /**
     * Not an exception: a caller with nothing to ask about gets zeros. Cash changes a person entered
     * by hand carry no source rule, and they are nobody's to count here.
     */
    @Test
    void noRuleAtAllCountsNothingRatherThanEverything() {
        ExpectedCashChangesByStatus counted = count(null,
                cashChange("CC1000000001", CashChangeStatus.PENDING, null),
                cashChange("CC1000000002", CashChangeStatus.CONFIRMED, null));

        assertThat(counted).isEqualTo(new ExpectedCashChangesByStatus(0, 0, 0));
    }

    @Test
    void anUnknownCashFlowIsRefusedWithTheContractsOwnException() {
        GetExpectedCashChangesByStatusQueryHandler handler =
                new GetExpectedCashChangesByStatusQueryHandler(new OnlyFindById(null));

        assertThatThrownBy(() -> handler.query(new GetExpectedCashChangesByStatusQuery(CASH_FLOW_ID, RULE)))
                .isInstanceOf(CashFlowNotFoundException.class);
    }

    private static ExpectedCashChangesByStatus count(String sourceRuleId, CashChangeSnapshot... cashChanges) {
        GetExpectedCashChangesByStatusQueryHandler handler =
                new GetExpectedCashChangesByStatusQueryHandler(
                        new OnlyFindById(CashFlow.from(snapshotHolding(cashChanges))));

        return handler.query(new GetExpectedCashChangesByStatusQuery(CASH_FLOW_ID, sourceRuleId));
    }

    private static CashFlowSnapshot snapshotHolding(CashChangeSnapshot... cashChanges) {
        Map<CashChangeId, CashChangeSnapshot> byId = new LinkedHashMap<>();
        for (CashChangeSnapshot cashChange : cashChanges) {
            byId.put(cashChange.cashChangeId(), cashChange);
        }
        return new CashFlowSnapshot(
                CASH_FLOW_ID,
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
                null);
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

    /**
     * The handler reads and nothing else, so the stub answers one method. The rest would be a lie
     * about what this test exercises.
     */
    private record OnlyFindById(CashFlow cashFlow) implements DomainCashFlowRepository {

        @Override
        public Optional<CashFlow> findById(CashFlowId id) {
            return Optional.ofNullable(cashFlow);
        }

        @Override
        public CashFlow save(CashFlow aggregate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DomainEvent> findDomainEvents(CashFlowId cashFlowId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CashFlow> findDetailsByUserId(UserId userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CashFlow> findOpenCashFlowsNeedingRollover(YearMonth targetPeriod) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsByUserIdAndName(UserId userId, String name) {
            throw new UnsupportedOperationException();
        }
    }
}
