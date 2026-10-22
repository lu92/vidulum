/**
 * Bytes become rows: a CSV file turns into the canonical transaction row and nothing else happens.
 *
 * <p><b>This section knows nothing about staging sessions, mappings, import jobs or classification.</b>
 * It reads a file, reports what it could not parse, and hands back
 * {@link com.multi.vidulum.bank_data_ingestion.canonical.BankCsvRow}. That one-way dependency is
 * checked by {@code CanonicalRowIsSelfContainedTest}, not left to good intentions - the whole point
 * of naming a section is that the rule can be written down.
 *
 * <p><b>What deliberately did <em>not</em> move here, against the first draft of the analysis:</b>
 * {@code upload_csv}. Reading it again showed what the plan had missed - {@code UploadCsvResult}
 * embeds {@code StageTransactionsResult}, and the command's whole purpose is to leave a staging
 * session behind. It parses <em>and</em> stages, so it is staging's entry point that happens to use
 * this parser, not an intake command. A section whose result type carries another section's result
 * is not a leaf, and calling it one would have made the first architecture rule a lie.
 *
 * <p>The one framework type here is {@code MultipartFile}: this parser reads what a person uploaded,
 * so the upload is part of its subject. Everything else it touches is the canonical row, Money, and
 * commons-csv.
 */
package com.multi.vidulum.bank_data_ingestion.intake;
