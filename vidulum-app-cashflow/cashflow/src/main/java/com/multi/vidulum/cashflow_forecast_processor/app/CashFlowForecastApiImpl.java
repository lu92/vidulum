package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.api.forecast.CashFlowForecastApi;
import com.multi.vidulum.cashflow.api.forecast.MonthStatus;
import com.multi.vidulum.cashflow.api.forecast.MonthStatuses;
import com.multi.vidulum.common.CashFlowId;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

/**
 * Answers {@link CashFlowForecastApi} from the statement this module maintains.
 *
 * <p>Package-private, like every implementation of a module contract - nobody outside names this
 * class. It reads the same statement {@code CashFlowForecastRestController} serves, and that
 * controller now goes through this class rather than traversing the forecasts itself: the walk and
 * the status mapping existed twice, which is one place too many for something whose two copies
 * would have to be changed together.
 */
@Component
@AllArgsConstructor
class CashFlowForecastApiImpl implements CashFlowForecastApi {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public MonthStatuses monthStatusesOf(CashFlowId cashFlowId) {
        return statementRepository.findByCashFlowId(cashFlowId)
                .map(statement -> new MonthStatuses(cashFlowId, extractMonthStatuses(statement)))
                .orElseGet(() -> MonthStatuses.none(cashFlowId));
    }

    private static Map<YearMonth, MonthStatus> extractMonthStatuses(CashFlowForecastStatement statement) {
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = statement.getForecasts();
        if (forecasts == null || forecasts.isEmpty()) {
            return Map.of();
        }

        Map<YearMonth, MonthStatus> result = new HashMap<>();
        for (Map.Entry<YearMonth, CashFlowMonthlyForecast> entry : forecasts.entrySet()) {
            CashFlowMonthlyForecast forecast = entry.getValue();
            if (forecast != null && forecast.getStatus() != null) {
                result.put(entry.getKey(), map(forecast.getStatus()));
            }
        }
        return result;
    }

    /**
     * The read model's status to the contract's. Exhaustive on purpose: a new status in
     * {@link CashFlowMonthlyForecast.Status} fails to compile here until the contract names it too,
     * which is what keeps the two vocabularies from drifting apart silently.
     */
    private static MonthStatus map(CashFlowMonthlyForecast.Status status) {
        return switch (status) {
            case IMPORT_PENDING -> MonthStatus.IMPORT_PENDING;
            case IMPORTED -> MonthStatus.IMPORTED;
            case ROLLED_OVER -> MonthStatus.ROLLED_OVER;
            case ATTESTED -> MonthStatus.ATTESTED;
            case ACTIVE -> MonthStatus.ACTIVE;
            case FORECASTED -> MonthStatus.FORECASTED;
        };
    }
}
