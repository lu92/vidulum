package com.multi.vidulum.cashflow.api.view;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a cash flow has taken in from elsewhere, indexed by the source system's id.
 *
 * <p><b>A named type rather than {@code Set<ImportedTransaction>}, and the reason is ergonomic
 * rather than aesthetic.</b> Every caller asks the same question - "have I seen this bank
 * transaction id before?" - and on a set of records {@code contains} is useless: you would need the
 * whole record to test for membership, so each consumer would build its own index first. This
 * carries the index, so the call site stays the one line it already was:
 *
 * <pre>
 * if (alreadyImported.contains(sourceId)) { ... }
 * </pre>
 *
 * <p>It replaces a {@code Set<String>} of ids. That was enough to answer the question but not to
 * say anything about the answer, which is why {@code DuplicateInfoJson.duplicateOf} ended up
 * holding the id of the row being examined instead of the one it collides with - see
 * {@link ImportedTransaction}.
 *
 * <p><b>Cash changes a person entered by hand are absent</b>, because they have no source id. An
 * empty result therefore means "nothing was imported", not "there is nothing here".
 */
public record ImportedTransactions(Map<String, ImportedTransaction> bySourceId) {

    public static ImportedTransactions none() {
        return new ImportedTransactions(Map.of());
    }

    public ImportedTransactions {
        bySourceId = bySourceId == null ? Map.of() : Map.copyOf(bySourceId);
    }

    /** Whether this source transaction is already recorded. The question every caller asks. */
    public boolean contains(String sourceId) {
        return sourceId != null && bySourceId.containsKey(sourceId);
    }

    /** What it was recorded as, for a caller that needs to say more than "duplicate". */
    public Optional<ImportedTransaction> find(String sourceId) {
        return sourceId == null ? Optional.empty() : Optional.ofNullable(bySourceId.get(sourceId));
    }

    /** How many came from a source system - not how many cash changes exist. */
    public int count() {
        return bySourceId.size();
    }

    public boolean isEmpty() {
        return bySourceId.isEmpty();
    }

    public Collection<ImportedTransaction> all() {
        return bySourceId.values();
    }
}
