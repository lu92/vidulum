package com.multi.vidulum.cashflow_forecast_processor.app;

import java.time.YearMonth;
import java.util.List;

public record CashFlowForecastSearchResult(
        YearMonth start,
        YearMonth end,
        List<CashFlowMonthlyForecast> forecasts
) {
}

