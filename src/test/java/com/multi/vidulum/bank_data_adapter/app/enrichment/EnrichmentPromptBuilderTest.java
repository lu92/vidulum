package com.multi.vidulum.bank_data_adapter.app.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentPromptBuilderTest {

    private EnrichmentPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new EnrichmentPromptBuilder(new ObjectMapper());
    }

    @Test
    void shouldBuildSystemPrompt() {
        // when
        String systemPrompt = promptBuilder.getSystemPrompt();

        // then
        assertThat(systemPrompt)
                .contains("transaction data enrichment specialist")
                .contains("MERCHANT EXTRACTION RULES")
                .contains("BANK_CATEGORY RULES")
                .contains("MERCHANT_CONFIDENCE")
                .contains("OUTPUT FORMAT")
                .contains("ERROR HANDLING")
                .contains("ŻABKA")
                .contains("NETFLIX")
                .contains("bankCategorySource");
    }

    @Test
    void shouldBuildUserPromptWithBatchInfo() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "ŻABKA POLSKA 4521", "Nr karty", ""),
                createTransaction(1, "Netflix.com", "Subscription", "Rozrywka")
        );

        // when
        String userPrompt = promptBuilder.buildUserPrompt(
                transactions, 1, 3, "Nest Bank", "pl");

        // then
        assertThat(userPrompt)
                .contains("Analyze these 2 transactions")
                .containsPattern("\"batchNumber\"\\s*:\\s*1")
                .containsPattern("\"totalBatches\"\\s*:\\s*3")
                .containsPattern("\"transactionsInBatch\"\\s*:\\s*2")
                .containsPattern("\"bankName\"\\s*:\\s*\"Nest Bank\"")
                .containsPattern("\"language\"\\s*:\\s*\"pl\"")
                .containsPattern("\"rowIndex\"\\s*:\\s*0")
                .containsPattern("\"rowIndex\"\\s*:\\s*1")
                .contains("ŻABKA POLSKA 4521")
                .contains("Netflix.com")
                .containsPattern("\"bankCategory\"\\s*:\\s*\"\"")  // Empty for first
                .containsPattern("\"bankCategory\"\\s*:\\s*\"Rozrywka\"");  // Filled for second
    }

    @Test
    void shouldHandleEmptyDescriptions() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "BIEDRONKA", null, ""),
                createTransaction(1, "ZUS", "", "")
        );

        // when
        String userPrompt = promptBuilder.buildUserPrompt(
                transactions, 1, 1, "Unknown", "pl");

        // then
        assertThat(userPrompt)
                .containsPattern("\"description\"\\s*:\\s*\"\"")
                .doesNotContain("null");
    }

    @Test
    void shouldHandlePolishCharacters() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "ŻABKA ĆWICZENIA ŁÓDŹ", "Płatność kartą", ""),
                createTransaction(1, "ŹRÓDŁO ŚWIĘTOŚĆ", "Przelew", "")
        );

        // when
        String userPrompt = promptBuilder.buildUserPrompt(
                transactions, 1, 1, "Test Bank", "pl");

        // then
        assertThat(userPrompt)
                .contains("ŻABKA ĆWICZENIA ŁÓDŹ")
                .contains("Płatność kartą")
                .contains("ŹRÓDŁO ŚWIĘTOŚĆ");
    }

    @Test
    void shouldNotIncludeAmountAndType() {
        // given - amount and type were removed from enrichment as they don't help
        // with merchant extraction or category inference
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "Employer", "Salary", ""),
                createTransaction(1, "Shop", "Purchase", "")
        );

        // when
        String userPrompt = promptBuilder.buildUserPrompt(
                transactions, 1, 1, "Bank", "pl");

        // then - verify amount and type are NOT in the prompt
        assertThat(userPrompt)
                .doesNotContain("\"type\"")
                .doesNotContain("\"amount\"")
                .doesNotContain("INFLOW")
                .doesNotContain("OUTFLOW");
    }

    @Test
    void shouldHandleNullBankNameAndLanguage() {
        // given
        List<TransactionForEnrichment> transactions = List.of(
                createTransaction(0, "Test", "desc", "")
        );

        // when
        String userPrompt = promptBuilder.buildUserPrompt(
                transactions, 1, 1, null, null);

        // then
        assertThat(userPrompt)
                .containsPattern("\"bankName\"\\s*:\\s*\"Unknown\"")
                .containsPattern("\"language\"\\s*:\\s*\"pl\"");
    }

    @Test
    void systemPromptShouldContainJsonOutputInstructions() {
        // when
        String systemPrompt = promptBuilder.getSystemPrompt();

        // then
        assertThat(systemPrompt)
                .contains("Return ONLY valid JSON")
                .contains("No markdown")
                .contains("\"success\": true")
                .contains("\"enrichedTransactions\"")
                .contains("\"rowIndex\"")
                .contains("\"merchant\"")
                .contains("\"merchantConfidence\"")
                .contains("\"bankCategory\"")
                .contains("\"bankCategorySource\"");
    }

    @Test
    void systemPromptShouldContainFallbackInstructions() {
        // when
        String systemPrompt = promptBuilder.getSystemPrompt();

        // then
        assertThat(systemPrompt)
                .contains("If you cannot determine merchant")
                .contains("fallback")
                .contains("NEVER return null")
                .contains("AI_FALLBACK");
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
