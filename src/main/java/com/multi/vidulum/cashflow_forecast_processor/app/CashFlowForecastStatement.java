package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.YearMonth;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashFlowForecastStatement {
    private CashFlowId cashFlowId;
    private Map<YearMonth, CashFlowMonthlyForecast> forecasts; // next 12 months

    public Optional<CashFlowMonthlyForecast.CashChangeLocation> locate(CashChangeId cashChangeId) {
        return forecasts.values().stream()
                .map(cashFlowMonthlyForecast -> {
                    Optional<CashFlowMonthlyForecast.CashChangeLocation> inflowCashChangeLocation = cashFlowMonthlyForecast.getCategorizedInFlows().stream()
                            .map(CashCategory::getTransactions)
                            .map(Map::values)
                            .flatMap(Collection::stream)
                            .flatMap(Collection::stream)
                            .filter(transactionDetails -> cashChangeId.equals(transactionDetails.getCashChangeId()))
                            .findFirst()
                            .map(transactionDetails -> new CashFlowMonthlyForecast.CashChangeLocation(
                                    transactionDetails.getCashChangeId(),
                                    cashFlowMonthlyForecast.getPeriod(),
                                    Type.INFLOW));

                    Optional<CashFlowMonthlyForecast.CashChangeLocation> outflowCashChangeLocation = cashFlowMonthlyForecast.getCategorizedOutFlows().stream()
                            .map(CashCategory::getTransactions)
                            .map(Map::values)
                            .flatMap(Collection::stream)
                            .flatMap(Collection::stream)
                            .filter(transactionDetails -> cashChangeId.equals(transactionDetails.getCashChangeId()))
                            .findFirst()
                            .map(transactionDetails -> new CashFlowMonthlyForecast.CashChangeLocation(
                                    transactionDetails.getCashChangeId(),
                                    cashFlowMonthlyForecast.getPeriod(),
                                    Type.OUTFLOW));

                    return inflowCashChangeLocation.or(() -> outflowCashChangeLocation);
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }
}
