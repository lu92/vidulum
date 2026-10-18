package com.multi.vidulum.cashflow.api;

/**
 * What a rollback removed.
 *
 * <p><b>Two integers the aggregate already computed and then threw away.</b>
 * {@code RollbackImportCommandHandler} counts the transactions and the custom categories, puts both
 * into {@code ImportRolledBackEvent}, logs them - and returns the post-rollback snapshot, where
 * {@code cashChanges} is empty and the numbers are therefore unrecoverable. Three consumers each
 * invented their own answer:
 *
 * <ul>
 *   <li>the endpoint returned <b>a hardcoded {@code 0}</b> with the comment "Actual count is
 *       logged, response shows post-rollback state" - so a browser calling it was told nothing had
 *       been deleted;</li>
 *   <li>bank-data-ingestion's HTTP client computed them by <b>subtraction across five requests</b>
 *       - two to read the state, one to delete, two to read it again - which is also racy: anything
 *       changing in between corrupts the count;</li>
 *   <li>the test double <b>did not perform the rollback at all</b>, returning the number of
 *       transactions still present (and {@code deleteCategories ? 0 : 0}), which made
 *       {@code shouldRollbackImport} pass for the wrong reason.</li>
 * </ul>
 *
 * <p>Carrying the counts back is the whole fix. Nothing new is computed anywhere.
 *
 * @param transactionsDeleted how many cash changes were removed
 * @param categoriesDeleted   how many custom categories were removed; {@code 0} when the caller
 *                            asked to keep them, and {@code Uncategorized} is never counted because
 *                            it is never removed
 */
public record RollbackResult(int transactionsDeleted, int categoriesDeleted) {
}
