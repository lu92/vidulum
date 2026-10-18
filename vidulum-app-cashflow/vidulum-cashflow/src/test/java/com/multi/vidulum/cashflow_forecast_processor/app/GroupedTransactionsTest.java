package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GroupedTransactions, in particular the idempotency guard
 * on {@link GroupedTransactions#addTransaction(Transaction)} which prevents
 * duplicate entries from Kafka at-least-once redelivery.
 */
class GroupedTransactionsTest {

    @Test
    @DisplayName("addTransaction should add a new transaction to the correct status group")
    void shouldAddNewTransaction() {
        GroupedTransactions gt = new GroupedTransactions();
        Transaction txn = paidTransaction("CC1000000001", 100);

        gt.addTransaction(txn);

        assertThat(gt.get(PAID)).hasSize(1);
        assertThat(gt.get(PAID).get(0).getCashChangeId()).isEqualTo(new CashChangeId("CC1000000001"));
    }

    @Test
    @DisplayName("addTransaction should reject duplicate cashChangeId and return false")
    void shouldRejectDuplicateCashChangeIdAndReturnFalse() {
        GroupedTransactions gt = new GroupedTransactions();
        Transaction txn1 = paidTransaction("CC1000000001", 100);
        Transaction txn2 = paidTransaction("CC1000000001", 100); // same cashChangeId

        boolean firstAdd = gt.addTransaction(txn1);
        boolean secondAdd = gt.addTransaction(txn2);

        assertThat(firstAdd).as("First add should succeed").isTrue();
        assertThat(secondAdd).as("Duplicate add should return false").isFalse();
        assertThat(gt.get(PAID))
                .as("Duplicate cashChangeId should not produce a second entry")
                .hasSize(1);
    }

    @Test
    @DisplayName("addTransaction should allow different cashChangeIds")
    void shouldAllowDifferentCashChangeIds() {
        GroupedTransactions gt = new GroupedTransactions();

        gt.addTransaction(paidTransaction("CC1000000001", 100));
        gt.addTransaction(paidTransaction("CC1000000002", 200));
        gt.addTransaction(paidTransaction("CC1000000003", 300));

        assertThat(gt.get(PAID)).hasSize(3);
    }

    @Test
    @DisplayName("addTransaction should allow same cashChangeId in different status groups")
    void shouldAllowSameCashChangeIdInDifferentStatusGroups() {
        // This scenario occurs during confirm: remove from EXPECTED, add to PAID.
        // The cashChangeId is the same but in a different status group.
        GroupedTransactions gt = new GroupedTransactions();

        gt.addTransaction(new Transaction(transactionDetails("CC1000000001", 100), EXPECTED));
        gt.addTransaction(new Transaction(transactionDetails("CC1000000001", 100), PAID));

        assertThat(gt.get(EXPECTED)).hasSize(1);
        assertThat(gt.get(PAID)).hasSize(1);
    }

    @Test
    @DisplayName("removeTransaction followed by addTransaction with same cashChangeId should work")
    void shouldAllowReaddAfterRemove() {
        GroupedTransactions gt = new GroupedTransactions();
        Transaction txn = new Transaction(transactionDetails("CC1000000001", 100), EXPECTED);

        gt.addTransaction(txn);
        assertThat(gt.get(EXPECTED)).hasSize(1);

        gt.removeTransaction(txn);
        assertThat(gt.get(EXPECTED)).isEmpty();

        // Re-add (e.g., moved back from another month) — should work
        Transaction txnUpdated = new Transaction(transactionDetails("CC1000000001", 200), PAID);
        gt.addTransaction(txnUpdated);
        assertThat(gt.get(PAID)).hasSize(1);
    }

    @Test
    @DisplayName("get() should return unmodifiable list — direct add bypassing addTransaction must throw")
    void getShouldReturnUnmodifiableList() {
        GroupedTransactions gt = new GroupedTransactions();

        List<TransactionDetails> paidList = gt.get(PAID);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                paidList.add(transactionDetails("CC1000000001", 100))
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("getTransactions() should return unmodifiable map and lists")
    void getTransactionsShouldReturnUnmodifiableMapAndLists() {
        GroupedTransactions gt = new GroupedTransactions();
        gt.addTransaction(paidTransaction("CC1000000001", 100));

        var txMap = gt.getTransactions();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                txMap.get(PAID).add(transactionDetails("CC1000000002", 200))
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    private Transaction paidTransaction(String cashChangeId, double amount) {
        return new Transaction(transactionDetails(cashChangeId, amount), PAID);
    }

    private TransactionDetails transactionDetails(String cashChangeId, double amount) {
        return new TransactionDetails(
                new CashChangeId(cashChangeId),
                new Name("Test"),
                Money.of(amount, "USD"),
                ZonedDateTime.parse("2021-06-01T00:00:00Z"),
                ZonedDateTime.parse("2021-06-15T00:00:00Z"),
                ZonedDateTime.parse("2021-06-15T00:00:00Z"),
                false
        );
    }
}
