package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.YearMonth;
import java.util.List;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;

@Data
@Builder
@AllArgsConstructor
public class CashFlowMonthlyForecast {
    private YearMonth period;
    private CashFlowStats cashFlowStats;
    private List<CashCategory> categorizedInFlows;
    private List<CashCategory> categorizedOutFlows;
    private Status status;

    public void removeFromInflows(Transaction transaction) {
        categorizedInFlows
                .get(0)
                .getGroupedTransactions()
                .removeTransaction(transaction);

        CashSummary inflowStatsToDecrease = cashFlowStats.getInflowStats();
        cashFlowStats
                .setInflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.actual().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.expected().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.gapToForecast().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.gapToForecast()
                        )
                );
    }

    public void addToInflows(Transaction transaction) {
        categorizedInFlows
                .get(0)
                .getGroupedTransactions()
                .addTransaction(transaction);

        CashSummary inflowStats = cashFlowStats.getInflowStats();
        cashFlowStats
                .setInflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? inflowStats.actual().plus(transaction.transactionDetails().getMoney()) : inflowStats.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? inflowStats.expected().plus(transaction.transactionDetails().getMoney()) : inflowStats.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? inflowStats.gapToForecast().plus(transaction.transactionDetails().getMoney()) : inflowStats.gapToForecast()
                        )
                );
    }

    public record CashChangeLocation(CashChangeId cashChangeId, YearMonth yearMonth, Type type,
                                     Transaction transaction) {
    }

    public enum Status {
        ATTESTED, ACTIVE, FORECASTED
    }
}
