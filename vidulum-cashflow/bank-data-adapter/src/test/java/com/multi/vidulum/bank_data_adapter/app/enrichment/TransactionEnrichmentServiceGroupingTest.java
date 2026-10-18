package com.multi.vidulum.bank_data_adapter.app.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TransactionEnrichmentServiceGroupingTest {

    private TransactionEnrichmentService service;

    @Mock
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        EnrichmentPromptBuilder promptBuilder = new EnrichmentPromptBuilder(objectMapper);
        EnrichmentResponseProcessor responseProcessor = new EnrichmentResponseProcessor(objectMapper);
        service = new TransactionEnrichmentService(chatModel, promptBuilder, responseProcessor);
    }

    @Test
    void shouldGroupTransactionsByExactName() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "ŻABKA POLSKA", "", ""),
                createTransaction(1, "BIEDRONKA", "", ""),
                createTransaction(2, "ŻABKA POLSKA", "", ""),
                createTransaction(3, "BIEDRONKA", "", ""),
                createTransaction(4, "ŻABKA POLSKA", "", "")
        );

        // when
        Map<String, TransactionGroup> groups = service.groupTransactions(transactions);

        // then
        assertThat(groups).hasSize(2);
        assertThat(groups.get("ŻABKA POLSKA").size()).isEqualTo(3);
        assertThat(groups.get("BIEDRONKA").size()).isEqualTo(2);
    }

    @Test
    void shouldNormalizeCaseForGrouping() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "Netflix", "", ""),
                createTransaction(1, "NETFLIX", "", ""),
                createTransaction(2, "netflix", "", "")
        );

        // when
        Map<String, TransactionGroup> groups = service.groupTransactions(transactions);

        // then
        assertThat(groups).hasSize(1);
        assertThat(groups.get("NETFLIX").size()).isEqualTo(3);
    }

    @Test
    void shouldTrimWhitespaceForGrouping() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "ZUS", "", ""),
                createTransaction(1, " ZUS ", "", ""),
                createTransaction(2, "  ZUS", "", "")
        );

        // when
        Map<String, TransactionGroup> groups = service.groupTransactions(transactions);

        // then
        assertThat(groups).hasSize(1);
        assertThat(groups.get("ZUS").size()).isEqualTo(3);
    }

    @Test
    void shouldSelectBestRepresentativeWithCategory() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "PROWIZJA", "", ""),         // empty
                createTransaction(1, "PROWIZJA", "", "Inne"),     // Inne
                createTransaction(2, "PROWIZJA", "", "Opłaty bankowe") // specific
        );

        // when
        Map<String, TransactionGroup> groups = service.groupTransactions(transactions);

        // then - representative should have the best bankCategory
        TransactionGroup group = groups.get("PROWIZJA");
        assertThat(group.getRepresentative().getBankCategory()).isEqualTo("Opłaty bankowe");
        // but rowIndex should be from first transaction
        assertThat(group.getRepresentative().getRowIndex()).isEqualTo(0);
    }

    @Test
    void shouldPropagateMerchantToAllTransactions() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "ŻABKA POLSKA 123", "", ""),
                createTransaction(1, "ŻABKA POLSKA 123", "", ""),
                createTransaction(2, "ŻABKA POLSKA 123", "", "")
        );

        Map<String, TransactionGroup> groups = service.groupTransactions(transactions);

        // Simulate AI result for representative
        List<EnrichedTransaction> enrichedRepresentatives = List.of(
                EnrichedTransaction.builder()
                        .rowIndex(0)
                        .merchant("ŻABKA")
                        .merchantConfidence(0.95)
                        .bankCategory("Zakupy spożywcze")
                        .bankCategorySource(EnrichedTransaction.BankCategorySource.AI_INFERRED)
                        .build()
        );

        // when
        List<EnrichedTransaction> allEnriched = service.propagateResults(
                enrichedRepresentatives, groups, transactions);

        // then
        assertThat(allEnriched).hasSize(3);
        assertThat(allEnriched).allMatch(e -> e.getMerchant().equals("ŻABKA"));
        assertThat(allEnriched).allMatch(e -> e.getMerchantConfidence() == 0.95);
    }

    @Test
    void shouldSelectivelyPropagateBankCategory() {
        // given - PROWIZJA with mixed original categories
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "PROWIZJA", "", ""),              // empty → should get AI result
                createTransaction(1, "PROWIZJA", "", "Opłaty bankowe"), // has value → should keep
                createTransaction(2, "PROWIZJA", "", ""),              // empty → should get AI result
                createTransaction(3, "PROWIZJA", "", "Inne")           // has value → should keep
        );

        Map<String, TransactionGroup> groups = service.groupTransactions(transactions);

        // AI returns category for representative
        List<EnrichedTransaction> enrichedRepresentatives = List.of(
                EnrichedTransaction.builder()
                        .rowIndex(0)
                        .merchant("PROWIZJA BANKOWA")
                        .merchantConfidence(0.8)
                        .bankCategory("Opłaty bankowe")  // AI inferred this
                        .bankCategorySource(EnrichedTransaction.BankCategorySource.AI_INFERRED)
                        .build()
        );

        // when
        List<EnrichedTransaction> allEnriched = service.propagateResults(
                enrichedRepresentatives, groups, transactions);

        // then
        assertThat(allEnriched).hasSize(4);

        // All should have same merchant
        assertThat(allEnriched).allMatch(e -> e.getMerchant().equals("PROWIZJA BANKOWA"));

        // Row 0: had empty → should get AI category
        EnrichedTransaction row0 = findByRowIndex(allEnriched, 0);
        assertThat(row0.getBankCategory()).isEqualTo("Opłaty bankowe");
        assertThat(row0.getBankCategorySource()).isEqualTo(EnrichedTransaction.BankCategorySource.AI_INFERRED);

        // Row 1: had "Opłaty bankowe" → should keep original
        EnrichedTransaction row1 = findByRowIndex(allEnriched, 1);
        assertThat(row1.getBankCategory()).isEqualTo("Opłaty bankowe");
        assertThat(row1.getBankCategorySource()).isEqualTo(EnrichedTransaction.BankCategorySource.ORIGINAL);

        // Row 2: had empty → should get AI category
        EnrichedTransaction row2 = findByRowIndex(allEnriched, 2);
        assertThat(row2.getBankCategory()).isEqualTo("Opłaty bankowe");
        assertThat(row2.getBankCategorySource()).isEqualTo(EnrichedTransaction.BankCategorySource.AI_INFERRED);

        // Row 3: had "Inne" → should keep original
        EnrichedTransaction row3 = findByRowIndex(allEnriched, 3);
        assertThat(row3.getBankCategory()).isEqualTo("Inne");
        assertThat(row3.getBankCategorySource()).isEqualTo(EnrichedTransaction.BankCategorySource.ORIGINAL);
    }

    @Test
    void shouldHandleMultipleGroupsWithDifferentCategories() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "NETFLIX", "", ""),
                createTransaction(1, "SPOTIFY", "", "Rozrywka"),
                createTransaction(2, "NETFLIX", "", "Inne"),
                createTransaction(3, "SPOTIFY", "", "")
        );

        Map<String, TransactionGroup> groups = service.groupTransactions(transactions);

        // AI results for both representatives
        List<EnrichedTransaction> enrichedRepresentatives = List.of(
                EnrichedTransaction.builder()
                        .rowIndex(0)
                        .merchant("NETFLIX")
                        .merchantConfidence(0.98)
                        .bankCategory("Rozrywka")
                        .bankCategorySource(EnrichedTransaction.BankCategorySource.AI_INFERRED)
                        .build(),
                EnrichedTransaction.builder()
                        .rowIndex(1)
                        .merchant("SPOTIFY")
                        .merchantConfidence(0.97)
                        .bankCategory("Rozrywka")
                        .bankCategorySource(EnrichedTransaction.BankCategorySource.ORIGINAL)
                        .build()
        );

        // when
        List<EnrichedTransaction> allEnriched = service.propagateResults(
                enrichedRepresentatives, groups, transactions);

        // then
        assertThat(allEnriched).hasSize(4);

        // NETFLIX group
        EnrichedTransaction netflix0 = findByRowIndex(allEnriched, 0);
        assertThat(netflix0.getMerchant()).isEqualTo("NETFLIX");
        assertThat(netflix0.getBankCategory()).isEqualTo("Rozrywka");
        assertThat(netflix0.getBankCategorySource()).isEqualTo(EnrichedTransaction.BankCategorySource.AI_INFERRED);

        EnrichedTransaction netflix2 = findByRowIndex(allEnriched, 2);
        assertThat(netflix2.getMerchant()).isEqualTo("NETFLIX");
        assertThat(netflix2.getBankCategory()).isEqualTo("Inne"); // kept original
        assertThat(netflix2.getBankCategorySource()).isEqualTo(EnrichedTransaction.BankCategorySource.ORIGINAL);

        // SPOTIFY group
        EnrichedTransaction spotify1 = findByRowIndex(allEnriched, 1);
        assertThat(spotify1.getMerchant()).isEqualTo("SPOTIFY");
        assertThat(spotify1.getBankCategory()).isEqualTo("Rozrywka"); // kept original
        assertThat(spotify1.getBankCategorySource()).isEqualTo(EnrichedTransaction.BankCategorySource.ORIGINAL);

        EnrichedTransaction spotify3 = findByRowIndex(allEnriched, 3);
        assertThat(spotify3.getMerchant()).isEqualTo("SPOTIFY");
        assertThat(spotify3.getBankCategory()).isEqualTo("Rozrywka"); // propagated from AI
        assertThat(spotify3.getBankCategorySource()).isEqualTo(EnrichedTransaction.BankCategorySource.ORIGINAL);
    }

    @Test
    void shouldHandleNullName() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, null, "", ""),
                createTransaction(1, "", "", ""),
                createTransaction(2, null, "", "")
        );

        // when
        Map<String, TransactionGroup> groups = service.groupTransactions(transactions);

        // then - all should be in same group (empty key)
        assertThat(groups).hasSize(1);
        assertThat(groups.get("").size()).isEqualTo(3);
    }

    @Test
    void shouldProvideCorrectRowIndexesForGroup() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "ZUS", "", ""),
                createTransaction(5, "BIEDRONKA", "", ""),
                createTransaction(10, "ZUS", "", ""),
                createTransaction(15, "ZUS", "", "")
        );

        // when
        Map<String, TransactionGroup> groups = service.groupTransactions(transactions);

        // then
        assertThat(groups.get("ZUS").getRowIndexes()).containsExactly(0, 10, 15);
        assertThat(groups.get("BIEDRONKA").getRowIndexes()).containsExactly(5);
    }

    private EnrichedTransaction findByRowIndex(List<EnrichedTransaction> list, int rowIndex) {
        return list.stream()
                .filter(e -> e.getRowIndex() == rowIndex)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No transaction with rowIndex " + rowIndex));
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
