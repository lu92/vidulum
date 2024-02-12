package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CashCategory {
    private Category category;
    private List<CashCategory> subCategories;
    private GroupedTransactions groupedTransactions;
    private Money totalValue;
}
