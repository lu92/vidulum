package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class CashCategory {
    private Category category;
    private List<CashCategory> subCategories;
    private Map<PaymentStatus, List<TransactionDetails>> transactions;
    private Money totalValue;

    public boolean hasInfoAbout(CashChangeId cashChangeId) {
        return transactions.values().stream().flatMap(Collection::stream)
                .anyMatch(transactionDetails -> cashChangeId.equals(transactionDetails.getCashChangeId()));
    }
}
