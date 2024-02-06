package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.common.Money;

record CashSummary(
        Money actual,
        Money expected,
        Money gapToForecast) {
}
