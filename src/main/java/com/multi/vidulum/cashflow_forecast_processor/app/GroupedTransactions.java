package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

import java.util.*;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;

/**
 * Groups transactions by payment status (PAID, EXPECTED, FORECAST).
 * <p>
 * <b>Mutation contract</b>: all additions go through {@link #addTransaction(Transaction)}
 * which enforces idempotency (duplicate {@code cashChangeId} within the same status group
 * is silently skipped). Public accessors ({@link #get(PaymentStatus)}, {@link #values()},
 * {@link #getTransactions()}) return <b>unmodifiable views</b> to prevent bypassing
 * the business methods.
 */
@EqualsAndHashCode
@AllArgsConstructor
public class GroupedTransactions {
    private Map<PaymentStatus, List<TransactionDetails>> transactions;

    public GroupedTransactions() {
        this.transactions = Map.of(
                PAID, new LinkedList<>(),
                EXPECTED, new LinkedList<>(),
                FORECAST, new LinkedList<>()
        );
    }

    /**
     * Returns the backing map wrapped in unmodifiable views (both the map and each list).
     * Used by serialization / mapping layers that need the full structure.
     */
    public Map<PaymentStatus, List<TransactionDetails>> getTransactions() {
        Map<PaymentStatus, List<TransactionDetails>> result = new LinkedHashMap<>();
        transactions.forEach((status, list) -> result.put(status, Collections.unmodifiableList(list)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Needed by MongoDB deserialization (Spring Data expects a setter matching the getter).
     */
    public void setTransactions(Map<PaymentStatus, List<TransactionDetails>> transactions) {
        this.transactions = transactions;
    }

    public Optional<Transaction> fetchTransaction(CashChangeId cashChangeId) {
        return transactions.entrySet().stream()
                .map(paymentTransactions -> paymentTransactions.getValue().stream()
                        .filter(transactionDetails -> cashChangeId.equals(transactionDetails.getCashChangeId()))
                        .findFirst()
                        .map(transactionDetails -> Map.entry(paymentTransactions.getKey(), transactionDetails)))
                .filter(Optional::isPresent)
                .findFirst()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(paymentStatusTransactionDetailsEntry -> new Transaction(paymentStatusTransactionDetailsEntry.getValue(), paymentStatusTransactionDetailsEntry.getKey()));
    }

    public Transaction findTransaction(CashChangeId cashChangeId) {
        return fetchTransaction(cashChangeId).orElseThrow(() -> new IllegalStateException(
                String.format("Cannot find transaction for CashChange [%s]", cashChangeId)));
    }

    public void removeTransaction(Transaction transaction) {
        transactions.get(transaction.paymentStatus()).remove(transaction.transactionDetails());
    }

    /**
     * Adds a transaction to the appropriate status group.
     * <p>
     * <b>Idempotent</b>: if a transaction with the same {@code cashChangeId} already exists
     * in the target status group, the call is a no-op. This guards against Kafka
     * at-least-once redelivery producing duplicate entries in the forecast read model.
     */
    public void addTransaction(Transaction transaction) {
        List<TransactionDetails> list = transactions.get(transaction.paymentStatus());
        boolean alreadyExists = list.stream()
                .anyMatch(td -> td.getCashChangeId().equals(
                        transaction.transactionDetails().getCashChangeId()));
        if (!alreadyExists) {
            list.add(transaction.transactionDetails());
        }
    }

    public void replace(ReplacementFrom from, ReplacementTo to) {
        transactions.get(from.status).remove(from.transactionDetails);
        transactions.get(to.status).add(to.transactionDetails);
    }

    /**
     * Returns an unmodifiable view of transaction lists grouped by status.
     */
    public Collection<List<TransactionDetails>> values() {
        return transactions.values().stream()
                .map(Collections::unmodifiableList)
                .toList();
    }

    /**
     * Returns an unmodifiable view of the transaction list for the given status.
     * To add transactions, use {@link #addTransaction(Transaction)}.
     */
    public List<TransactionDetails> get(PaymentStatus paymentStatus) {
        return Collections.unmodifiableList(transactions.get(paymentStatus));
    }

    public record ReplacementFrom(PaymentStatus status, TransactionDetails transactionDetails) {
        public static ReplacementFrom from(PaymentStatus status, TransactionDetails transactionDetails) {
            return new ReplacementFrom(status, transactionDetails);
        }
    }

    public record ReplacementTo(PaymentStatus status, TransactionDetails transactionDetails) {
        public static ReplacementTo to(PaymentStatus status, TransactionDetails transactionDetails) {
            return new ReplacementTo(status, transactionDetails);
        }
    }
}
