package com.multi.vidulum.bank_data_ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two {@code TransactionClassification} enums agree.
 *
 * <p><b>Why there are two.</b> bank-data-adapter and bank-data-ingestion never exchange this type.
 * The adapter appends a CSV column - {@code ",classification,classificationReason,location"} - and
 * ingestion parses the text with its own {@code fromString}. The contract between them is a column
 * name and a vocabulary of words, so a shared Java type would be a dependency invented to express
 * something the CSV already expresses. A Maven module briefly existed for it and was removed once
 * the shape of the boundary was actually measured.
 *
 * <p><b>Why this test exists anyway.</b> {@code fromString} answers {@code UNKNOWN} for anything it
 * does not recognise, which is correct - a producer may know words a consumer does not - but it
 * also means a constant added on one side and forgotten on the other turns every such transaction
 * into {@code UNKNOWN} <i>silently</i>. Silence is the problem, not the tolerance. This test is the
 * noise.
 *
 * <p>It lives in {@code vidulum-cashflow}'s test tree because that is the only place both modules
 * are visible: ingestion is a separate artifact that depends on contracts alone, and the adapter
 * stays here. Same arrangement as the seven integration tests next door.
 */
class TransactionClassificationVocabularyTest {

    private static Set<String> namesOf(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("every word the adapter can write, ingestion can read")
    void theVocabulariesMatch() {
        Set<String> written = namesOf(
                com.multi.vidulum.bank_data_adapter.domain.TransactionClassification.class);
        Set<String> read = namesOf(
                com.multi.vidulum.bank_data_ingestion.domain.TransactionClassification.class);

        assertThat(read)
                .as("a word the adapter writes and ingestion does not know becomes UNKNOWN for "
                        + "every transaction carrying it, with nothing in the logs to say so")
                .containsAll(written);
        assertThat(written)
                .as("and the other direction too: a constant ingestion expects but the adapter "
                        + "never produces is dead code pretending to be a case")
                .containsAll(read);
    }

    @Test
    @DisplayName("ingestion parses every word the adapter can write, rather than defaulting")
    void everyWrittenWordParsesToItself() {
        for (var written : com.multi.vidulum.bank_data_adapter.domain.TransactionClassification.values()) {
            var parsed = com.multi.vidulum.bank_data_ingestion.domain.TransactionClassification
                    .fromString(written.name());

            assertThat(parsed.name())
                    .as("round trip through the CSV column for [%s]", written.name())
                    .isEqualTo(written.name());
        }
    }

    @Test
    @DisplayName("an unrecognised word is UNKNOWN, not an exception")
    void anUnknownWordDoesNotBreakAnImport() {
        var parsed = com.multi.vidulum.bank_data_ingestion.domain.TransactionClassification
                .fromString("SOMETHING_A_FUTURE_ADAPTER_INVENTS");

        assertThat(parsed.name())
                .as("402 rows must not fail because one column value is newer than this module")
                .isEqualTo("UNKNOWN");
    }
}
