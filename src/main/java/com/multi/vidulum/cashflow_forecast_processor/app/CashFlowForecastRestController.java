package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

@AllArgsConstructor
@RestController
@RequestMapping("/cash-flow-forecast")
public class CashFlowForecastRestController {

    private final CashFlowForecastStatementRepository statementRepository;
    private final CashFlowForecastMapper mapper;

    @GetMapping("/{cashFlowId}")
    public CashFlowForecastDto.CashFlowForecastStatementJson getForecastStatement(
            @PathVariable("cashFlowId") String cashFlowId) {
        CashFlowId id = CashFlowId.of(cashFlowId);
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(id)
                .orElseThrow(() -> new CashFlowDoesNotExistsException(id));
        return mapper.map(statement);
    }

    /**
     * Get month statuses for a given CashFlow.
     * Used by bank-data-ingestion module to determine which months allow import.
     *
     * @param cashFlowId the CashFlow identifier
     * @return response with cashFlowId and map of YearMonth to ForecastMonthStatus
     */
    @GetMapping("/{cashFlowId}/month-statuses")
    public CashFlowForecastDto.MonthStatusesResponse getMonthStatuses(@PathVariable("cashFlowId") String cashFlowId) {
        CashFlowId id = CashFlowId.of(cashFlowId);
        Map<YearMonth, CashFlowForecastDto.ForecastMonthStatus> monthStatuses = statementRepository.findByCashFlowId(id)
                .map(this::extractMonthStatuses)
                .orElse(Map.of());

        return CashFlowForecastDto.MonthStatusesResponse.builder()
                .cashFlowId(cashFlowId)
                .monthStatuses(monthStatuses)
                .build();
    }

    private Map<YearMonth, CashFlowForecastDto.ForecastMonthStatus> extractMonthStatuses(CashFlowForecastStatement statement) {
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = statement.getForecasts();
        if (forecasts == null || forecasts.isEmpty()) {
            return Map.of();
        }

        Map<YearMonth, CashFlowForecastDto.ForecastMonthStatus> result = new HashMap<>();
        for (Map.Entry<YearMonth, CashFlowMonthlyForecast> entry : forecasts.entrySet()) {
            YearMonth month = entry.getKey();
            CashFlowMonthlyForecast forecast = entry.getValue();
            if (forecast != null && forecast.getStatus() != null) {
                CashFlowForecastDto.ForecastMonthStatus status = mapStatus(forecast.getStatus());
                result.put(month, status);
            }
        }
        return result;
    }

    private CashFlowForecastDto.ForecastMonthStatus mapStatus(CashFlowMonthlyForecast.Status status) {
        return switch (status) {
            case IMPORT_PENDING -> CashFlowForecastDto.ForecastMonthStatus.IMPORT_PENDING;
            case IMPORTED -> CashFlowForecastDto.ForecastMonthStatus.IMPORTED;
            case ROLLED_OVER -> CashFlowForecastDto.ForecastMonthStatus.ROLLED_OVER;
            case ATTESTED -> CashFlowForecastDto.ForecastMonthStatus.ATTESTED;
            case ACTIVE -> CashFlowForecastDto.ForecastMonthStatus.ACTIVE;
            case FORECASTED -> CashFlowForecastDto.ForecastMonthStatus.FORECASTED;
        };
    }
}
