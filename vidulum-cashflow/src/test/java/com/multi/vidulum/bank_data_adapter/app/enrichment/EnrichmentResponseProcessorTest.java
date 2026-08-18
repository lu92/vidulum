package com.multi.vidulum.bank_data_adapter.app.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentResponseProcessorTest {

    private EnrichmentResponseProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new EnrichmentResponseProcessor(new ObjectMapper());
    }

    @Test
    void shouldParseValidJsonResponse() {
        // given
        String aiResponse = """
            {
              "success": true,
              "enrichedTransactions": [
                {
                  "rowIndex": 0,
                  "merchant": "ŻABKA",
                  "merchantConfidence": 0.95,
                  "bankCategory": "Zakupy spożywcze",
                  "bankCategorySource": "AI_INFERRED"
                },
                {
                  "rowIndex": 1,
                  "merchant": "NETFLIX",
                  "merchantConfidence": 0.98,
                  "bankCategory": "Rozrywka",
                  "bankCategorySource": "ORIGINAL"
                }
              ],
              "processingNotes": "All transactions processed successfully"
            }
            """;

        List<TransactionForEnrichment> originals = List.of(
                createTransaction(0, "ŻABKA POLSKA 4521 WARSZAWA", "", ""),
                createTransaction(1, "NETFLIX.COM", "", "Inne")
        );

        // when
        EnrichmentBatchResult result = processor.process(aiResponse, originals);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getEnrichedTransactions()).hasSize(2);
        assertThat(result.getEnrichedTransactions().get(0).getMerchant()).isEqualTo("ŻABKA");
        assertThat(result.getEnrichedTransactions().get(0).getMerchantConfidence()).isEqualTo(0.95);
        assertThat(result.getEnrichedTransactions().get(1).getMerchant()).isEqualTo("NETFLIX");
        assertThat(result.getProcessingNotes()).isEqualTo("All transactions processed successfully");
    }

    @Test
    void shouldExtractJsonFromMarkdownCodeBlock() {
        // given
        String aiResponse = """
            Here is the enrichment result:

            ```json
            {
              "success": true,
              "enrichedTransactions": [
                {
                  "rowIndex": 0,
                  "merchant": "ZUS",
                  "merchantConfidence": 0.98,
                  "bankCategory": "Podatki i składki",
                  "bankCategorySource": "AI_INFERRED"
                }
              ]
            }
            ```

            The transaction was identified as ZUS payment.
            """;

        List<TransactionForEnrichment> originals = List.of(
                createTransaction(0, "ZUS", "składki ZUS", "")
        );

        // when
        EnrichmentBatchResult result = processor.process(aiResponse, originals);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getEnrichedTransactions()).hasSize(1);
        assertThat(result.getEnrichedTransactions().get(0).getMerchant()).isEqualTo("ZUS");
    }

    @Test
    void shouldUseFallbackForEmptyResponse() {
        // given
        String aiResponse = "";
        List<TransactionForEnrichment> originals = List.of(
                createTransaction(0, "Some Transaction", "desc", "Inne")
        );

        // when
        EnrichmentBatchResult result = processor.process(aiResponse, originals);

        // then
        assertThat(result.isSuccess()).isTrue(); // Fallback is still "success" for flow continuation
        assertThat(result.getEnrichedTransactions()).hasSize(1);
        assertThat(result.getEnrichedTransactions().get(0).getMerchantConfidence()).isLessThan(0.5);
        assertThat(result.getProcessingNotes()).contains("Fallback");
    }

    @Test
    void shouldUseFallbackForInvalidJson() {
        // given
        String aiResponse = "This is not JSON at all!";
        List<TransactionForEnrichment> originals = List.of(
                createTransaction(0, "Transaction", "desc", "")
        );

        // when
        EnrichmentBatchResult result = processor.process(aiResponse, originals);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getEnrichedTransactions()).hasSize(1);
        assertThat(result.getProcessingNotes()).contains("Fallback");
    }

    @Test
    void shouldRepairPartialResult() {
        // given - AI returned only 1 of 2 transactions
        String aiResponse = """
            {
              "success": true,
              "enrichedTransactions": [
                {
                  "rowIndex": 0,
                  "merchant": "ALLEGRO",
                  "merchantConfidence": 0.95,
                  "bankCategory": "Zakupy",
                  "bankCategorySource": "AI_INFERRED"
                }
              ]
            }
            """;

        List<TransactionForEnrichment> originals = List.of(
                createTransaction(0, "ALLEGRO.PL", "", ""),
                createTransaction(1, "Missing Transaction", "", "Inne")
        );

        // when
        EnrichmentBatchResult result = processor.process(aiResponse, originals);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getEnrichedTransactions()).hasSize(2);

        // First one from AI
        assertThat(result.getEnrichedTransactions().get(0).getMerchant()).isEqualTo("ALLEGRO");
        assertThat(result.getEnrichedTransactions().get(0).getMerchantConfidence()).isEqualTo(0.95);

        // Second one filled with fallback
        var secondTxn = result.getEnrichedTransactions().stream()
                .filter(t -> t.getRowIndex() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(secondTxn.getMerchantConfidence()).isLessThan(0.5);
    }

    @Test
    void shouldKeepOriginalBankCategoryWhenMarkedAsOriginal() {
        // given
        String aiResponse = """
            {
              "success": true,
              "enrichedTransactions": [
                {
                  "rowIndex": 0,
                  "merchant": "BIEDRONKA",
                  "merchantConfidence": 0.95,
                  "bankCategory": "Zakupy spożywcze",
                  "bankCategorySource": "ORIGINAL"
                }
              ]
            }
            """;

        List<TransactionForEnrichment> originals = List.of(
                createTransaction(0, "BIEDRONKA SKLEP", "", "Zakupy spożywcze")
        );

        // when
        EnrichmentBatchResult result = processor.process(aiResponse, originals);
        List<EnrichedTransaction> domain = processor.toDomainObjects(result);

        // then
        assertThat(domain).hasSize(1);
        assertThat(domain.get(0).getBankCategorySource())
                .isEqualTo(EnrichedTransaction.BankCategorySource.ORIGINAL);
    }

    @Test
    void shouldConvertToDomainObjects() {
        // given
        String aiResponse = """
            {
              "success": true,
              "enrichedTransactions": [
                {
                  "rowIndex": 0,
                  "merchant": "ORLEN",
                  "merchantConfidence": 0.92,
                  "bankCategory": "Transport",
                  "bankCategorySource": "AI_INFERRED"
                }
              ]
            }
            """;

        List<TransactionForEnrichment> originals = List.of(
                createTransaction(0, "ORLEN STACJA 123", "Paliwo", "")
        );

        EnrichmentBatchResult batchResult = processor.process(aiResponse, originals);

        // when
        List<EnrichedTransaction> domain = processor.toDomainObjects(batchResult);

        // then
        assertThat(domain).hasSize(1);
        EnrichedTransaction txn = domain.get(0);
        assertThat(txn.getRowIndex()).isEqualTo(0);
        assertThat(txn.getMerchant()).isEqualTo("ORLEN");
        assertThat(txn.getMerchantConfidence()).isEqualTo(0.92);
        assertThat(txn.getBankCategory()).isEqualTo("Transport");
        assertThat(txn.getBankCategorySource())
                .isEqualTo(EnrichedTransaction.BankCategorySource.AI_INFERRED);
    }

    @Test
    void shouldHandleNullResponse() {
        // given
        List<TransactionForEnrichment> originals = List.of(
                createTransaction(0, "Test", "", "")
        );

        // when
        EnrichmentBatchResult result = processor.process(null, originals);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getEnrichedTransactions()).hasSize(1);
        assertThat(result.getProcessingNotes()).contains("Empty AI response");
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
