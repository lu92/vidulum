package com.multi.vidulum.bank_data_adapter.app.enrichment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionGroupTest {

    @Test
    void shouldAddTransactionAndTrackRowIndex() {
        // given
        TransactionGroup group = TransactionGroup.builder()
                .groupKey("ŻABKA")
                .build();

        TransactionForEnrichment txn1 = createTransaction(0, "ŻABKA", "", "");
        TransactionForEnrichment txn2 = createTransaction(5, "ŻABKA", "", "");
        TransactionForEnrichment txn3 = createTransaction(10, "ŻABKA", "", "");

        // when
        group.addTransaction(txn1);
        group.addTransaction(txn2);
        group.addTransaction(txn3);

        // then
        assertThat(group.size()).isEqualTo(3);
        assertThat(group.getRowIndexes()).containsExactly(0, 5, 10);
    }

    @Test
    void shouldSelectFirstTransactionAsRepresentativeWhenAllHaveSameCategory() {
        // given
        TransactionGroup group = TransactionGroup.builder()
                .groupKey("BIEDRONKA")
                .build();

        // All have empty category
        TransactionForEnrichment txn1 = createTransaction(0, "BIEDRONKA", "", "");
        TransactionForEnrichment txn2 = createTransaction(1, "BIEDRONKA", "", "");

        // when
        group.addTransaction(txn1);
        group.addTransaction(txn2);

        // then
        assertThat(group.getRepresentative().getRowIndex()).isEqualTo(0);
    }

    @Test
    void shouldPreferSpecificCategoryOverEmpty() {
        // given
        TransactionGroup group = TransactionGroup.builder()
                .groupKey("NETFLIX")
                .build();

        TransactionForEnrichment txnEmpty = createTransaction(0, "NETFLIX", "", "");
        TransactionForEnrichment txnSpecific = createTransaction(1, "NETFLIX", "", "Rozrywka");

        // when
        group.addTransaction(txnEmpty);
        group.addTransaction(txnSpecific);

        // then - representative should use specific category
        assertThat(group.getRepresentative().getBankCategory()).isEqualTo("Rozrywka");
        // but rowIndex should be from first transaction
        assertThat(group.getRepresentative().getRowIndex()).isEqualTo(0);
    }

    @Test
    void shouldPreferSpecificCategoryOverInne() {
        // given
        TransactionGroup group = TransactionGroup.builder()
                .groupKey("ORLEN")
                .build();

        TransactionForEnrichment txnInne = createTransaction(0, "ORLEN", "", "Inne");
        TransactionForEnrichment txnSpecific = createTransaction(1, "ORLEN", "", "Transport");

        // when
        group.addTransaction(txnInne);
        group.addTransaction(txnSpecific);

        // then
        assertThat(group.getRepresentative().getBankCategory()).isEqualTo("Transport");
    }

    @Test
    void shouldPreferInneOverEmpty() {
        // given
        TransactionGroup group = TransactionGroup.builder()
                .groupKey("ZUS")
                .build();

        TransactionForEnrichment txnEmpty = createTransaction(0, "ZUS", "", "");
        TransactionForEnrichment txnInne = createTransaction(1, "ZUS", "", "Inne");

        // when
        group.addTransaction(txnEmpty);
        group.addTransaction(txnInne);

        // then
        assertThat(group.getRepresentative().getBankCategory()).isEqualTo("Inne");
    }

    @Test
    void shouldTrackOriginalBankCategories() {
        // given
        TransactionGroup group = TransactionGroup.builder()
                .groupKey("PROWIZJA")
                .build();

        TransactionForEnrichment txn1 = createTransaction(0, "PROWIZJA", "", "");
        TransactionForEnrichment txn2 = createTransaction(1, "PROWIZJA", "", "Opłaty bankowe");
        TransactionForEnrichment txn3 = createTransaction(2, "PROWIZJA", "", "Inne");

        // when
        group.addTransaction(txn1);
        group.addTransaction(txn2);
        group.addTransaction(txn3);

        // then
        assertThat(group.getOriginalBankCategories())
                .containsEntry(0, "")
                .containsEntry(1, "Opłaty bankowe")
                .containsEntry(2, "Inne");
    }

    @Test
    void shouldIdentifyEmptyBankCategory() {
        // given
        TransactionGroup group = TransactionGroup.builder()
                .groupKey("TEST")
                .build();

        group.addTransaction(createTransaction(0, "TEST", "", ""));
        group.addTransaction(createTransaction(1, "TEST", "", "Kategoria"));
        group.addTransaction(createTransaction(2, "TEST", "", null));
        group.addTransaction(createTransaction(3, "TEST", "", "   "));

        // when/then
        assertThat(group.hadEmptyBankCategory(0)).isTrue();
        assertThat(group.hadEmptyBankCategory(1)).isFalse();
        assertThat(group.hadEmptyBankCategory(2)).isTrue();
        assertThat(group.hadEmptyBankCategory(3)).isTrue();
    }

    @Test
    void shouldKeepRepresentativeNameAndDescriptionFromFirst() {
        // given
        TransactionGroup group = TransactionGroup.builder()
                .groupKey("ALLEGRO")
                .build();

        TransactionForEnrichment txn1 = createTransaction(0, "ALLEGRO SP ZOO", "zakupy", "");
        TransactionForEnrichment txn2 = createTransaction(1, "ALLEGRO SP ZOO", "inne zakupy", "Zakupy");

        // when
        group.addTransaction(txn1);
        group.addTransaction(txn2);

        // then - name and description from first, category from second
        assertThat(group.getRepresentative().getName()).isEqualTo("ALLEGRO SP ZOO");
        assertThat(group.getRepresentative().getDescription()).isEqualTo("zakupy");
        assertThat(group.getRepresentative().getBankCategory()).isEqualTo("Zakupy");
    }

    @Test
    void shouldHandleCaseInsensitiveInneComparison() {
        // given
        TransactionGroup group = TransactionGroup.builder()
                .groupKey("TEST")
                .build();

        // "inne" lowercase vs "Inne" titlecase - both should be treated as "Inne"
        TransactionForEnrichment txnLower = createTransaction(0, "TEST", "", "inne");
        TransactionForEnrichment txnSpecific = createTransaction(1, "TEST", "", "Zakupy");

        // when
        group.addTransaction(txnLower);
        group.addTransaction(txnSpecific);

        // then - specific category should be preferred
        assertThat(group.getRepresentative().getBankCategory()).isEqualTo("Zakupy");
    }

    private TransactionForEnrichment createTransaction(int rowIndex, String name,
                                                        String description, String bankCategory) {
        return TransactionForEnrichment.builder()
                .rowIndex(rowIndex)
                .name(name)
                .description(description)
                .bankCategory(bankCategory)
                .build();
    }
}
