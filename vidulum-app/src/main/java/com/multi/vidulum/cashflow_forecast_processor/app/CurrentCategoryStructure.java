package com.multi.vidulum.cashflow_forecast_processor.app;

import java.time.ZonedDateTime;
import java.util.List;

public record CurrentCategoryStructure(
        List<CategoryNode> inflowCategoryStructure,
        List<CategoryNode> outflowCategoryStructure,
        ZonedDateTime lastUpdated
) {
}