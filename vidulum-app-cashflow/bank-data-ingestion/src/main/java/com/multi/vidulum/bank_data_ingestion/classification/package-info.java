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
 *   <li><b>The classification progress on the session</b> - {@code AiCategorizationStatus} and the
 *       five fields on {@code StagingSessionEntity}. The plan said they would move here; reading the
 *       code said otherwise. They are written through the session's own methods
 *       ({@code startAiCategorization}, {@code completeAiCategorization}, {@code failAiCategorization}),
 *       and {@code skipAiCategorization} is called by <b>staging's own</b>
 *       {@code ForceUncategorizedCommandHandler}. A state machine's state belongs to whoever holds
 *       it, so it is staging's vocabulary - and leaving it there is exactly what lets this section
 *       be a leaf. Had it moved, {@code staging} would depend on {@code classification} and the rule
 *       would be false on day one.</li>
 *   <li><b>The memory of learned patterns</b> - gone in F66, together with the engine. This section
 *       asks a contract and keeps nothing.</li>
 * </ul>
 *
 * <p>What is still owed here is naming, not structure: the folders are called {@code ai_categorize}
 * and {@code accept_ai_suggestions} after the technology rather than the capability, and the
 * endpoints keep their {@code /ai-categorize} and {@code /accept-ai} paths <b>by decision of the
 * code owner</b> - the URLs stay even when the inside is renamed. That is step 1 of F67; this is
 * step 3.
 */
package com.multi.vidulum.bank_data_ingestion.classification;
