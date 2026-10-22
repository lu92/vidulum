/**
 * Deciding what a transaction is: ask whoever classifies, record what came back on the session.
 *
 * <p><b>This section is a leaf, and that is its whole purpose.</b> Nothing in this module depends on
 * it, which is what makes the classifier replaceable: a different engine means a different
 * implementation of {@code TransactionCategorizationApi} (F66) and, at most, a different section
 * here - and {@code intake}, {@code staging}, {@code mappings} and {@code importing} do not notice.
 * The rule is checked in {@code CanonicalRowIsSelfContainedTest}, not hoped for.
 *
 * <p><b>No domain and no infrastructure of its own, deliberately.</b> Two things could have been
 * dragged in here and both belong elsewhere:
 *
 * <ul>
 *   <li><b>The classification progress on the session</b> - {@code ClassificationStatus} and the
 *       five fields on {@code StagingSessionEntity}. The plan said they would move here; reading the
 *       code said otherwise. They are written through the session's own methods
 *       ({@code startClassification}, {@code completeClassification}, {@code failClassification}),
 *       and {@code skipClassification} is called by <b>staging's own</b>
 *       {@code ForceUncategorizedCommandHandler}. A state machine's state belongs to whoever holds
 *       it, so it is staging's vocabulary - and leaving it there is exactly what lets this section
 *       be a leaf. Had it moved, {@code staging} would depend on {@code classification} and the rule
 *       would be false on day one.</li>
 *   <li><b>The memory of learned patterns</b> - gone in F66, together with the engine. This section
 *       asks a contract and keeps nothing.</li>
 * </ul>
 *
 * <p><b>The names now say what happens, not who does it</b> - {@code classify},
 * {@code accept_classification}, {@code ClassificationStatus}, {@code CategorizationSuggestions}.
 * The session stopped storing tokens and an estimated cost: those were the supplier's units in a
 * document that will outlive any supplier, and nothing read them.
 *
 * <p><b>The URLs deliberately did not follow</b>, by decision of the code owner:
 * {@code /ai-categorize} and {@code /accept-ai} stay. So an address here does not match the name of
 * the command it sends, and that mismatch is a choice about an external surface rather than an
 * oversight - the one thing in this module still named after a supplier.
 */
package com.multi.vidulum.bank_data_ingestion.classification;
