package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.*;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;

@Data
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

    public void addTransaction(Transaction transaction) {
        transactions.get(transaction.paymentStatus()).add(transaction.transactionDetails());
    }

    public void replace(ReplacementFrom from, ReplacementTo to) {
        transactions.get(from.status).remove(from.transactionDetails);
        transactions.get(to.status).add(to.transactionDetails);
    }

    public Collection<List<TransactionDetails>> values() {
        return transactions.values();
    }

    public List<TransactionDetails> get(PaymentStatus paymentStatus) {
        return transactions.get(paymentStatus);
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
