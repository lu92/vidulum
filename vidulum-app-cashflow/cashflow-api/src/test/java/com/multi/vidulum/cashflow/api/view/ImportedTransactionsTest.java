package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The indexed collection, and why it is one.
 *
 * <p>It replaced a {@code Set<String>} of source ids. The set answered the only question anyone
 * asked - "seen this before?" - but could say nothing about the answer, which is how
 * {@code DuplicateInfoJson.duplicateOf} came to hold the id of the row being examined rather than
 * the one it collides with.
 *
 * <p>A {@code Set<ImportedTransaction>} would have fixed the data and broken the ergonomics:
 * {@code contains} on a set of records needs the whole record, so every consumer would build this
 * index itself. Hence a type that carries it.
 */
class ImportedTransactionsTest {

    private static final ZonedDateTime PAID = ZonedDateTime.parse("2021-11-14T13:45:30Z[UTC]");

    private static ImportedTransaction entry(String sourceId, String cashChangeId) {
        return new ImportedTransaction(
                sourceId,
                CashChangeId.of(cashChangeId),
                PAID,
                Money.of(99.99, "GBP"),
                new CategoryName("Groceries"));
    }

    private static ImportedTransactions two() {
        return new ImportedTransactions(Map.of(
                "NEST-A", entry("NEST-A", "CC1000000001"),
                "NEST-B", entry("NEST-B", "CC1000000002")));
    }

    @Test
    void answersWhetherASourceTransactionIsAlreadyRecorded() {
        assertThat(two().contains("NEST-A")).isTrue();
        assertThat(two().contains("NEST-C")).isFalse();
    }

    @Test
    void askingAboutNothingIsNotAMatch() {
        assertThat(two().contains(null))
                .as("a staged row with no source id must not collide with everything")
                .isFalse();
        assertThat(two().find(null)).isEmpty();
    }

    @Test
    void handsBackWhatTheSourceTransactionBecame() {
        assertThat(two().find("NEST-B"))
                .get()
                .extracting(ImportedTransaction::cashChangeId)
                .as("the answer duplicateOf was always supposed to carry")
                .isEqualTo(CashChangeId.of("CC1000000002"));
        assertThat(two().find("NEST-C")).isEmpty();
    }

    @Test
    void anEmptyResultMeansNothingWasImportedNotThatNothingExists() {
        assertThat(ImportedTransactions.none().isEmpty()).isTrue();
        assertThat(ImportedTransactions.none().count()).isZero();
        assertThat(ImportedTransactions.none().contains("NEST-A")).isFalse();
    }

    @Test
    void countsSourceTransactionsNotCashChanges() {
        assertThat(two().count())
                .as("hand-entered cash changes have no source id and are simply not here")
                .isEqualTo(2);
        assertThat(two().all()).hasSize(2);
    }

    @Test
    void aNullMapIsTreatedAsEmptyRatherThanExploding() {
        assertThat(new ImportedTransactions(null).isEmpty())
                .as("deserialisation can hand us a null field; a reporting call should not throw")
                .isTrue();
    }

    @Test
    void theIndexIsCopiedSoCallersCannotMutateIt() {
        Map<String, ImportedTransaction> mutable = new HashMap<>();
        mutable.put("NEST-A", entry("NEST-A", "CC1000000001"));
        ImportedTransactions imported = new ImportedTransactions(mutable);

        mutable.put("NEST-Z", entry("NEST-Z", "CC1000000009"));

        assertThat(imported.contains("NEST-Z"))
                .as("a view of cashflow's state must not change under the caller's feet")
                .isFalse();
        assertThatThrownBy(() -> imported.bySourceId().put("NEST-Y", entry("NEST-Y", "CC1000000008")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
