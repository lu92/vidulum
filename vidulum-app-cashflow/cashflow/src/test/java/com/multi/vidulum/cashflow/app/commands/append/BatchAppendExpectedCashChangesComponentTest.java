package com.multi.vidulum.cashflow.app.commands.append;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.CashChangeStatus;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CashFlowEventEmitter;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.CategoryIsArchivedException;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.DueDateOutsideAllowedRangeException;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.domain.OperationNotAllowedInSetupModeException;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.BusinessIdGenerator;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Appending many expected cash changes as one operation.
 *
 * <p>Two properties are the reason this command exists, and each has a test here that fails if the
 * implementation goes back to a loop:
 *
 * <ul>
 *   <li><b>atomic</b> - a batch with one bad item changes nothing at all. The loop it replaces
 *       wrote everything up to the bad occurrence and left it there, while the caller recorded none
 *       of the ids, so nothing could find those cash changes again;</li>
 *   <li><b>one of everything</b> - one aggregate load, one save, one event, one reservation of ids,
 *       whatever the count. Counting the calls is the only assertion that actually measures that.</li>
 * </ul>
 */
class BatchAppendExpectedCashChangesComponentTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final UserId OWNER = UserId.of("U10000001");
    private static final YearMonth ACTIVE = YearMonth.of(2022, 1);
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]");
    private static final String RULE_ID = "RR00000001";

    private final CountingRepository repository = new CountingRepository();
    private final CashFlowEventEmitter emitter = mock(CashFlowEventEmitter.class);
    private final BusinessIdGenerator idGenerator = mock(BusinessIdGenerator.class);

    private final BatchAppendExpectedCashChangesCommandHandler handler =
            new BatchAppendExpectedCashChangesCommandHandler(
                    repository,
                    emitter,
                    idGenerator,
                    Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void aBatchIsOneLoadOneSaveOneEventAndOneReservationOfIds() {
        repository.holds(openCashFlow());
        idsReserved(12);

        List<CashChangeId> ids = handler.handle(command(
                IntStream.rangeClosed(1, 12).mapToObj(month -> rent(YearMonth.of(2022, month))).toList()));

        assertThat(ids).hasSize(12);
        assertThat(repository.loads).as("one load for twelve items").isEqualTo(1);
        assertThat(repository.saves).as("one save for twelve items").isEqualTo(1);
        verify(idGenerator, times(1)).generateCashChangeIds(12);
        verify(emitter, times(1)).emit(org.mockito.ArgumentMatchers.any());
        assertThat(repository.saved.getUncommittedEvents())
                .as("one event for the batch, not one per item")
                .hasSize(1)
                .first()
                .isInstanceOf(CashFlowEvent.ExpectedCashChangesBatchAppendedEvent.class);
    }

    @Test
    void theIdsComeBackInTheOrderTheChangesWereHandedOver() {
        repository.holds(openCashFlow());
        idsReserved(3);

        List<CashChangeId> ids = handler.handle(command(List.of(
                rent(YearMonth.of(2022, 1)), rent(YearMonth.of(2022, 2)), rent(YearMonth.of(2022, 3)))));

        assertThat(ids).extracting(CashChangeId::id)
                .containsExactly("CC1000000001", "CC1000000002", "CC1000000003");

        Map<CashChangeId, CashChangeSnapshot> appended = repository.saved.getSnapshot().cashChanges();
        assertThat(appended).hasSize(3);
        assertThat(appended.get(ids.get(0)).dueDate()).isEqualTo(dueDate(YearMonth.of(2022, 1)));
        assertThat(appended.get(ids.get(2)).dueDate()).isEqualTo(dueDate(YearMonth.of(2022, 3)));
        assertThat(appended.values()).allSatisfy(change -> {
            assertThat(change.status()).isEqualTo(CashChangeStatus.PENDING);
            assertThat(change.sourceRuleId()).isEqualTo(RULE_ID);
            assertThat(change.created()).isEqualTo(NOW);
        });
    }

    /**
     * The property the loop could not have. One due date past the forecast window and the whole
     * batch is refused - nothing saved, nothing emitted, and no ids taken from the sequence.
     */
    @Test
    void oneBadDueDateLeavesNothingBehind() {
        repository.holds(openCashFlow());

        assertThatThrownBy(() -> handler.handle(command(List.of(
                rent(YearMonth.of(2022, 1)),
                rent(YearMonth.of(2022, 2)),
                rent(YearMonth.of(2024, 7)),      // far outside activePeriod + 11
                rent(YearMonth.of(2022, 3))))))
                .isInstanceOf(DueDateOutsideAllowedRangeException.class);

        assertThat(repository.saves).isZero();
        verifyNoInteractions(emitter);
        verify(idGenerator, never()).generateCashChangeIds(anyInt());
    }

    @Test
    void anArchivedCategoryRefusesTheWholeBatchToo() {
        repository.holds(openCashFlow(category("Rent"), archived("Old subscriptions")));

        assertThatThrownBy(() -> handler.handle(command(List.of(
                rent(YearMonth.of(2022, 1)),
                change("Old subscriptions", YearMonth.of(2022, 2))))))
                .isInstanceOf(CategoryIsArchivedException.class);

        assertThat(repository.saves).isZero();
        verifyNoInteractions(emitter);
    }

    @Test
    void aCashFlowStillInSetupRefusesBeforeLookingAtAnyItem() {
        repository.holds(cashFlow(CashFlow.CashFlowStatus.SETUP, category("Rent")));

        assertThatThrownBy(() -> handler.handle(command(List.of(rent(YearMonth.of(2022, 1))))))
                .isInstanceOf(OperationNotAllowedInSetupModeException.class);

        assertThat(repository.saves).isZero();
        verify(idGenerator, never()).generateCashChangeIds(anyInt());
    }

    @Test
    void anUnknownCashFlowIsRefusedWithTheContractsOwnException() {
        assertThatThrownBy(() -> handler.handle(command(List.of(rent(YearMonth.of(2022, 1))))))
                .isInstanceOf(CashFlowNotFoundException.class);
    }

    /**
     * An empty batch is not an error - a rule whose occurrences all fall outside the window simply
     * has nothing to append - and it must not cost a load, a save or an id.
     */
    @Test
    void anEmptyBatchTouchesNothing() {
        repository.holds(openCashFlow());

        assertThat(handler.handle(command(List.of()))).isEmpty();

        assertThat(repository.loads).isZero();
        assertThat(repository.saves).isZero();
        verifyNoInteractions(emitter);
        verifyNoInteractions(idGenerator);
    }

    private void idsReserved(int count) {
        when(idGenerator.generateCashChangeIds(count)).thenReturn(
                IntStream.rangeClosed(1, count)
                        .mapToObj(i -> CashChangeId.of("CC%010d".formatted(1_000_000_000L + i)))
                        .toList());
    }

    private static BatchAppendExpectedCashChangesCommand command(
            List<BatchAppendExpectedCashChangesCommand.ExpectedCashChangeToAppend> changes) {
        return new BatchAppendExpectedCashChangesCommand(CASH_FLOW_ID, RULE_ID, changes);
    }

    private static BatchAppendExpectedCashChangesCommand.ExpectedCashChangeToAppend rent(YearMonth month) {
        return change("Rent", month);
    }

    private static BatchAppendExpectedCashChangesCommand.ExpectedCashChangeToAppend change(
            String categoryName, YearMonth month) {
        return new BatchAppendExpectedCashChangesCommand.ExpectedCashChangeToAppend(
                new CategoryName(categoryName),
                new Name("Rent " + month),
                new Description("generated by rule"),
                Money.of(1500, "PLN"),
                Type.OUTFLOW,
                dueDate(month));
    }

    private static ZonedDateTime dueDate(YearMonth month) {
        return month.atDay(15).atStartOfDay(ZoneOffset.UTC);
    }

    private static CashFlow openCashFlow(Category... outflow) {
        Category[] categories = outflow.length == 0 ? new Category[]{category("Rent")} : outflow;
        return cashFlow(CashFlow.CashFlowStatus.OPEN, categories);
    }

    private static CashFlow cashFlow(CashFlow.CashFlowStatus status, Category... outflow) {
        return CashFlow.from(new CashFlowSnapshot(
                CASH_FLOW_ID,
                OWNER,
                new Name("Nest Bank"),
                new Description("current account"),
                BankAccount.fromIban("Nest Bank", "PL61109010140000071219812874",
                        Currency.of("PLN"), Money.of(0, "PLN"), null),
                status,
                Map.of(),
                ACTIVE,
                ACTIVE,
                Money.of(0, "PLN"),
                List.of(),
                List.of(outflow),
                NOW,
                NOW,
                null,
                new Checksum("irrelevant-here"),
                null));
    }

    private static Category category(String name) {
        return Category.builder()
                .categoryName(new CategoryName(name))
                .subCategories(List.of())
                .isModifiable(true)
                .build();
    }

    private static Category archived(String name) {
        return Category.builder()
                .categoryName(new CategoryName(name))
                .subCategories(List.of())
                .isModifiable(true)
                .archived(true)
                .build();
    }

    /**
     * Counts what the handler does to storage, because "one load and one save per batch" is the
     * claim and nothing else can check it.
     */
    private static final class CountingRepository implements DomainCashFlowRepository {

        private CashFlow held;
        private CashFlow saved;
        private int loads;
        private int saves;

        void holds(CashFlow cashFlow) {
            this.held = cashFlow;
        }

        @Override
        public Optional<CashFlow> findById(CashFlowId id) {
            loads++;
            return Optional.ofNullable(held);
        }

        @Override
        public CashFlow save(CashFlow aggregate) {
            saves++;
            saved = aggregate;
            return aggregate;
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
