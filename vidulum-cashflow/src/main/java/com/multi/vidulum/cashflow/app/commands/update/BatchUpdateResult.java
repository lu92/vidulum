package com.multi.vidulum.cashflow.app.commands.update;

/**
 * Result of batch update operation.
 *
 * @param updatedCount number of cash changes successfully updated
 * @param skippedCount number of cash changes skipped (e.g., CONFIRMED status)
 */
public record BatchUpdateResult(
        int updatedCount,
        int skippedCount) {
}
