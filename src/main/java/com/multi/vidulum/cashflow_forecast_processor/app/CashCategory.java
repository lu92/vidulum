package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CashCategory {
    private CategoryName categoryName;
    private Category category;
    private List<CashCategory> subCategories;
    private GroupedTransactions groupedTransactions;

    /**
     * updated with {@link CashFlowEvent.CashChangeConfirmedEvent}
     */
    private Money totalPaidValue;
}
