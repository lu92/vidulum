package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CashCategory {
    private Category category;
    private List<CashCategory> subCategories;
//    private Map<PaymentStatus, List<TransactionDetails>> transactions;
    private GroupedTransactions groupedTransactions;
    private Money totalValue;

//    public Transaction findTransaction(CashChangeId cashChangeId) {
//        return transactions.entrySet().stream()
//                .map(paymentTransactions -> paymentTransactions.getValue().stream()
//                        .filter(transactionDetails -> cashChangeId.equals(transactionDetails.getCashChangeId()))
//                        .findFirst()
//                        .map(transactionDetails -> Map.entry(paymentTransactions.getKey(), transactionDetails)))
//                .filter(Optional::isPresent)
//                .findFirst()
//                .filter(Optional::isPresent)
//                .map(Optional::get)
//                .map(paymentStatusTransactionDetailsEntry -> new Transaction(paymentStatusTransactionDetailsEntry.getValue(), paymentStatusTransactionDetailsEntry.getKey()))
//                .orElseThrow(() -> new IllegalStateException(
//                        String.format("Cannot find transaction for CashChange [%s]", cashChangeId)));
//    }
}
