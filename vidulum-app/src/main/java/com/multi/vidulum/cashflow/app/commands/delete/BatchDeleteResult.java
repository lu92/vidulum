package com.multi.vidulum.cashflow.app.commands.delete;

/**
 * Result of batch delete operation.
 *
 * @param deletedCount number of cash changes successfully deleted
 * @param skippedCount number of cash changes skipped (e.g., CONFIRMED status)
 */
public record BatchDeleteResult(
        int deletedCount,
        int skippedCount) {
}
