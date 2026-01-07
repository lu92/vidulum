package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.CategoryOrigin;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Represents a category in a monthly forecast with its transactions and metadata.
 * Contains archiving information for UI filtering (show active vs all categories).
 */
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

    private Budgeting budgeting;

    /** Whether this category is archived (hidden from new transaction creation) */
    private boolean archived;

    /** Start date of validity (null = valid from the beginning) */
    private ZonedDateTime validFrom;

    /** End date of validity (set when archived) */
    private ZonedDateTime validTo;

    /** Origin of this category (SYSTEM, IMPORTED, USER_CREATED) */
    private CategoryOrigin origin;

    /**
     * Archive this category, marking it as hidden for new transactions.
     */
    public void archive(ZonedDateTime archiveTimestamp) {
        this.archived = true;
        this.validTo = archiveTimestamp;
    }

    /**
     * Unarchive this category, making it available for new transactions again.
     */
    public void unarchive() {
        this.archived = false;
        this.validTo = null;
    }
}
