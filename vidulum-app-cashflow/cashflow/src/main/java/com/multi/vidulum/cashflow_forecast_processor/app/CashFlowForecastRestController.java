package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.forecast.CashFlowForecastApi;
import com.multi.vidulum.cashflow.api.forecast.MonthStatus;
import com.multi.vidulum.cashflow.api.forecast.MonthStatuses;
import com.multi.vidulum.common.CashFlowId;
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
    private final CashFlowForecastApi forecastApi;

    @GetMapping("/cf={cashFlowId}")
    public CashFlowForecastDto.CashFlowForecastStatementJson getForecastStatement(
            @PathVariable("cashFlowId") String cashFlowId) {
        CashFlowId id = CashFlowId.of(cashFlowId);
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(id)
                .orElseThrow(() -> new CashFlowNotFoundException(id));
        return mapper.map(statement);
    }

    /**
     * Get month statuses for a given CashFlow.
     *
     * <p>The answer comes from {@link CashFlowForecastApi} rather than from a walk over the
     * statement done here, because this endpoint is no longer the only caller: a module asking the
     * same question in-process gets the same traversal and the same mapping, and only the wire
     * shape is this controller's business.
     *
     * @param cashFlowId the CashFlow identifier
     * @return response with cashFlowId and map of YearMonth to ForecastMonthStatus
     */
    @GetMapping("/cf={cashFlowId}/month-statuses")
    public CashFlowForecastDto.MonthStatusesResponse getMonthStatuses(@PathVariable("cashFlowId") String cashFlowId) {
        MonthStatuses monthStatuses = forecastApi.monthStatusesOf(CashFlowId.of(cashFlowId));

        Map<YearMonth, CashFlowForecastDto.ForecastMonthStatus> body = new HashMap<>();
        monthStatuses.byMonth().forEach((month, status) -> body.put(month, map(status)));

        return CashFlowForecastDto.MonthStatusesResponse.builder()
                .cashFlowId(cashFlowId)
                .monthStatuses(body)
                .build();
    }

    private static CashFlowForecastDto.ForecastMonthStatus map(MonthStatus status) {
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
