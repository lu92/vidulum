package com.multi.vidulum.cashflow;

import com.multi.vidulum.cashflow.app.CashFlowHttpActor;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two writers who both read the same cash flow: one wins, the other is told, and the winner's work
 * survives.
 *
 * <p>Task F35. Until {@code CashFlowEntity} carried a {@code @Version}, {@code save} replaced the
 * whole document unconditionally, so the second writer silently discarded the first's work - both
 * answered 2xx, both emitted their events, and the aggregate and the forecast projection were left
 * permanently disagreeing with nothing to notice. There is nothing clever to assert about that
 * state; the only fix is for the second write to fail.
 *
 * <p>The race is staged rather than threaded, deliberately: two reads followed by two writes is
 * exactly the interleaving that loses data, and staging it makes the test deterministic. A real
 * race adds nothing an assertion could check.
 */
@ExtendWith(SpringExtension.class)
class CashFlowConcurrentWriteTest extends CashFlowIntegrationTest {

    private CashFlowHttpActor actor;

    @BeforeEach
    void setUp() {
        registerAndAuthenticate();
        actor = new CashFlowHttpActor(restTemplate, port);
        actor.setJwtToken(accessToken);
    }

    @Test
    void theSecondWriterIsRefusedAndTheFirstWritersWorkStands() {
        CashFlowId cashFlowId = aCashFlowWithOneCategory();

        // Both read the same state - the interleaving that used to lose one of them
        CashFlow first = domainCashFlowRepository.findById(cashFlowId).orElseThrow();
        CashFlow second = domainCashFlowRepository.findById(cashFlowId).orElseThrow();

        CashChangeId fromFirst = CashChangeId.of("CC1000000001");
        CashChangeId fromSecond = CashChangeId.of("CC1000000002");

        first.apply(appended(cashFlowId, fromFirst, "From the first writer"));
        domainCashFlowRepository.save(first);

        second.apply(appended(cashFlowId, fromSecond, "From the second writer"));

        assertThatThrownBy(() -> domainCashFlowRepository.save(second))
                .as("the document moved on, so this write is no longer an overwrite but a refusal")
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(domainCashFlowRepository.findById(cashFlowId).orElseThrow().getSnapshot().cashChanges())
                .as("the first writer's work is still there, and the second's never landed")
                .containsKey(fromFirst)
                .doesNotContainKey(fromSecond);
    }

    @Test
    void aWriterWhoReadsAgainAfterLosingSucceeds() {
        CashFlowId cashFlowId = aCashFlowWithOneCategory();

        CashFlow stale = domainCashFlowRepository.findById(cashFlowId).orElseThrow();

        CashFlow other = domainCashFlowRepository.findById(cashFlowId).orElseThrow();
        other.apply(appended(cashFlowId, CashChangeId.of("CC1000000003"), "Winner"));
        domainCashFlowRepository.save(other);

        stale.apply(appended(cashFlowId, CashChangeId.of("CC1000000004"), "Loser"));
        assertThatThrownBy(() -> domainCashFlowRepository.save(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // What a retry looks like: read the current state and apply the change to that
        CashFlow fresh = domainCashFlowRepository.findById(cashFlowId).orElseThrow();
        fresh.apply(appended(cashFlowId, CashChangeId.of("CC1000000004"), "Loser, retried"));
        domainCashFlowRepository.save(fresh);

        assertThat(domainCashFlowRepository.findById(cashFlowId).orElseThrow().getSnapshot().cashChanges())
                .as("both changes are present once the loser re-read")
                .containsKeys(CashChangeId.of("CC1000000003"), CashChangeId.of("CC1000000004"));
    }

    /**
     * A cash flow that has never been saved carries no version, and the first save has to be an
     * insert rather than a conditional update - otherwise nothing could ever be created.
     */
    @Test
    void theFirstSaveOfANewCashFlowNeedsNoVersion() {
        CashFlowId cashFlowId = aCashFlowWithOneCategory();

        assertThat(domainCashFlowRepository.findById(cashFlowId).orElseThrow().getSnapshot().version())
                .as("and once saved it has one, which is what makes the next write conditional")
                .isNotNull();
    }

    private CashFlowId aCashFlowWithOneCategory() {
        return CashFlowId.of(actor.createCashFlow(userId, "Concurrency " + System.nanoTime(), "PLN"));
    }

    private static CashFlowEvent.ExpectedCashChangeAppendedEvent appended(
            CashFlowId cashFlowId, CashChangeId cashChangeId, String name) {
        return new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name(name),
                new Description("staged concurrency test"),
                Money.of(100, "PLN"),
                Type.OUTFLOW,
                ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2022-01-15T00:00:00Z"),
                null,
                false);
    }
}
