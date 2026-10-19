package com.multi.vidulum.cashflow.app.commands.append;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.CategoryIsArchivedException;
import com.multi.vidulum.cashflow.domain.DueDateOutsideAllowedRangeException;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.CategoryName;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * What has to be true before an expected cash change may be appended.
 *
 * <p>One copy of these two checks, shared by the single-append handler and the batch one. They used
 * to be private methods of the former; a second copy in the latter is the shape that drifts, and
 * the two would drift in the worst possible way - a batch quietly accepting what a single append
 * refuses.
 *
 * <p><b>The order is the single handler's, to the letter:</b> the category first, then the due date.
 * A request can break both at once and which exception comes out decides the {@code ErrorCode} the
 * endpoint answers with.
 */
final class ExpectedCashChangeRules {

    private ExpectedCashChangeRules() {
    }

    /**
     * @throws CategoryIsArchivedException         when the named category exists but is archived.
     *                                             A category that does not exist at all is left to
     *                                             the domain, which is where that refusal lives.
     * @throws DueDateOutsideAllowedRangeException when the due date is outside
     *                                             {@code [activePeriod, activePeriod + 11]}
     */
    static void requireAppendable(CashFlowSnapshot snapshot, CategoryName categoryName, Type type, ZonedDateTime dueDate) {
        requireCategoryNotArchived(snapshot, categoryName, type);
        requireDueDateInRange(dueDate, snapshot.activePeriod());
    }

    private static void requireCategoryNotArchived(CashFlowSnapshot snapshot, CategoryName categoryName, Type type) {
        List<Category> categories = type == Type.INFLOW
                ? snapshot.inflowCategories()
                : snapshot.outflowCategories();

        if (findCategory(categories, categoryName, true) != null) {
            return;
        }
        // No active category found - if an archived one holds the name, that is the refusal.
        // Otherwise there is no such category at all, which the domain layer answers for.
        if (findCategory(categories, categoryName, false) != null) {
            throw new CategoryIsArchivedException(categoryName);
        }
    }

    /**
     * Allowed range: the active period through the last forecasted month, {@code activePeriod + 11}.
     */
    private static void requireDueDateInRange(ZonedDateTime dueDate, YearMonth activePeriod) {
        YearMonth dueDateMonth = YearMonth.from(dueDate);
        YearMonth maxAllowedMonth = activePeriod.plusMonths(11);

        if (dueDateMonth.isBefore(activePeriod) || dueDateMonth.isAfter(maxAllowedMonth)) {
            throw new DueDateOutsideAllowedRangeException(dueDate, activePeriod);
        }
    }

    /**
     * @param active whether to look for an active category or an archived one
     */
    private static Category findCategory(List<Category> categories, CategoryName categoryName, boolean active) {
        for (Category category : categories) {
            if (category.getCategoryName().equals(categoryName)
                    && (active ? category.isActive() : category.isArchived())) {
                return category;
            }
            Category found = findCategory(category.getSubCategories(), categoryName, active);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
