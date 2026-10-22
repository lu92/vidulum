/**
 * The staging session: what a bank's rows become before anyone commits them to a cash flow.
 *
 * <p>The largest section, and the one that owns the module's two central collections - the session
 * and the staged rows. Everything here answers one question: <b>is this row ready to be imported,
 * and if not, what does the owner have to decide.</b> Validation, duplicate detection against what
 * the cash flow already holds, which months accept a write, self-transfer detection, and the forcing
 * of leftovers to Uncategorized.
 *
 * <p><b>It reads the canonical row and the mappings; it does not know the importer or the
 * classifier.</b> That direction is the import flow itself: staging decides, {@code importing} then
 * writes what staging decided, and classification is asked <em>by</em> the session rather than
 * asking it. Checked in {@code CanonicalRowIsSelfContainedTest}.
 *
 * <p><b>Two things live here that a plan would have put elsewhere, both measured rather than
 * assumed:</b>
 *
 * <ul>
 *   <li>{@code upload_csv} - it parses a file, which sounds like {@code intake}, but its result
 *       embeds {@code StageTransactionsResult} and its whole purpose is to leave a session behind.
 *       It is staging's entry point that happens to use intake's parser.</li>
 *   <li>The classification progress on the session - {@code ClassificationStatus} and the cost
 *       fields. They are written through the session's own methods, and
 *       {@code ForceUncategorizedCommandHandler} - staging's own - calls
 *       {@code skipClassification()}. A state machine's state belongs to whoever holds it, and
 *       leaving it here is what lets the classification section be a leaf.</li>
 * </ul>
 *
 * <p>What is still owed: the {@code Ai} prefixes on those session fields say the supplier rather
 * than the capability, which is step 1 of F67.
 */
package com.multi.vidulum.bank_data_ingestion.staging;
