package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CategoryName;

import java.util.List;

public record CategoryNode(
        CategoryNode parentCategoryNode,
        CategoryName categoryName,
        List<CategoryNode> nodes
) {
}
