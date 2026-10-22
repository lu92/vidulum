/**
 * The canonical transaction row: what a bank statement becomes once it stops being a file.
 *
 * <p><b>These four types live together because they belong to no single section.</b>
 * {@code intake} produces them from a CSV, {@code staging} stores them, and the classification
 * section reads them - so putting them in any one of those three would make the other two depend on
 * a neighbour's internals for the one thing they actually agree on. It is the same reasoning that
 * gave {@code ImportWindow} a home in {@code cashflow-api}: a rule two sides need belongs to
 * neither of them.
 *
 * <p><b>Nothing here may depend on the rest of the module.</b> That is the one rule of this package
 * and it is checked, not hoped for ({@code CanonicalRowIsSelfContainedTest}): a vocabulary that
 * knows its users is not a vocabulary. In practice it means these types reach only for
 * shared-kernel values ({@code Money}) and cashflow's contract ({@code Type}) - never for a staging
 * session, a mapping or an import job.
 *
 * <p>{@code TransactionClassification} is deliberately <b>duplicated</b> with
 * {@code bank_data_adapter}, and that is not an oversight: the two modules never exchange the type,
 * they exchange a CSV column and a vocabulary of words, so {@code fromString} answers
 * {@code UNKNOWN} for anything it does not recognise. The drift that makes this safe also makes it
 * silent, which is why {@code TransactionClassificationVocabularyTest} fails if either side gains a
 * constant the other lacks. Moving the class here changed its package, not that arrangement.
 *
 * <p>Deliberately <b>not</b> here: {@code StagedTransaction} and its validation. A staged row is a
 * canonical row plus what staging decided about it - duplicate, unmapped, which month - and those
 * decisions are staging's, not everybody's.
 */
package com.multi.vidulum.bank_data_ingestion.canonical;
