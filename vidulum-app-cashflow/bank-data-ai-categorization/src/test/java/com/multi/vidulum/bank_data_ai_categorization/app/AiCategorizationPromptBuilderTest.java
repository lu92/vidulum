package com.multi.vidulum.bank_data_ai_categorization.app;

import com.multi.vidulum.cashflow.api.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import com.multi.vidulum.bank_data_ai_categorization.api.CategoryStructure;

/**
 * Unit tests for AiCategorizationPromptBuilder.
 * Verifies that prompts include proper category hierarchy with type separation.
 */
class AiCategorizationPromptBuilderTest {

    private AiCategorizationPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new AiCategorizationPromptBuilder();
    }

    @Test
    @DisplayName("Should include system prompt with category type guidance")
    void shouldIncludeSystemPromptWithCategoryTypeGuidance() {
        // when - pass language parameter (default Polish)
        String systemPrompt = promptBuilder.getSystemPrompt("pl");

        // then
        assertThat(systemPrompt)
                .contains("OUTFLOW")
                .contains("INFLOW")
                .contains("Polish")
                .contains("hierarchical");
    }

    @Test
    @DisplayName("Should format categories with type separation in prompt")
    void shouldFormatCategoriesWithTypeSeparationInPrompt() {
        // given
        CategoryStructure structure = new CategoryStructure(
                List.of(
                        new CategoryStructure.CategoryNode("Wynagrodzenie", List.of(
                                new CategoryStructure.CategoryNode("Pensja"),
                                new CategoryStructure.CategoryNode("Premia")
                        ))
                ),
                List.of(
                        new CategoryStructure.CategoryNode("Żywność", List.of(
                                new CategoryStructure.CategoryNode("Zakupy spożywcze"),
                                new CategoryStructure.CategoryNode("Restauracje")
                        ))
                ),
                java.util.Set.of("Wynagrodzenie", "Pensja", "Premia"),
                java.util.Set.of("Żywność", "Zakupy spożywcze", "Restauracje")
        );

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "BIEDRONKA",
                        "Biedronka zakupy",
                        null, // sampleMerchant
                        null, // averageMerchantConfidence
                        "Zakupy żywności",
                        Type.OUTFLOW,
                        3,
                        new BigDecimal("150.00"),
                        "Zakupy",
                        List.of("tx1", "tx2", "tx3"),
                        null // counterpartyAccount
                )
        );

        // when - pass empty cachedPatternIntents and Polish language
        String prompt = promptBuilder.buildUserPrompt(patterns, structure, List.of(), "pl");

        // then - should contain type-separated sections
        assertThat(prompt).contains("EXISTING CATEGORIES");
        assertThat(prompt).contains("INFLOW (przychody)");
        assertThat(prompt).contains("OUTFLOW (wydatki)");

        // then - should contain hierarchical structure
        assertThat(prompt).contains("Wynagrodzenie");
        assertThat(prompt).contains("Pensja");
        assertThat(prompt).contains("Premia");
        assertThat(prompt).contains("Żywność");
        assertThat(prompt).contains("Zakupy spożywcze");
        assertThat(prompt).contains("Restauracje");
    }

    @Test
    @DisplayName("Should include isExistingCategory and reason in JSON format")
    void shouldIncludeIsExistingCategoryAndReasonInJsonFormat() {
        // given
        CategoryStructure structure = CategoryStructure.empty();
        List<PatternDeduplicator.PatternGroup> patterns = List.of();

        // when
        String prompt = promptBuilder.buildUserPrompt(patterns, structure, List.of(), "pl");

        // then - should include new JSON fields
        assertThat(prompt).contains("isExistingCategory");
        assertThat(prompt).contains("reason");
        assertThat(prompt).contains("unrecognizedPatterns");
    }

    @Test
    @DisplayName("Should include type matching rule in prompt")
    void shouldIncludeTypeMatchingRuleInPrompt() {
        // given
        CategoryStructure structure = CategoryStructure.empty();
        List<PatternDeduplicator.PatternGroup> patterns = List.of();

        // when
        String prompt = promptBuilder.buildUserPrompt(patterns, structure, List.of(), "pl");

        // then - should emphasize type matching
        assertThat(prompt).contains("TYPE MATCHING");
        assertThat(prompt).contains("OUTFLOW patterns MUST map to OUTFLOW categories");
        assertThat(prompt).contains("INFLOW patterns MUST map to INFLOW categories");
    }

    @Test
    @DisplayName("Should format pattern groups correctly")
    void shouldFormatPatternGroupsCorrectly() {
        // given
        CategoryStructure structure = CategoryStructure.empty();
        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "NETFLIX",
                        "Netflix subscription",
                        "NETFLIX", // sampleMerchant
                        0.95, // averageMerchantConfidence
                        "Monthly streaming service",
                        Type.OUTFLOW,
                        1,
                        new BigDecimal("49.99"),
                        "Subscriptions",
                        List.of("tx1"),
                        null // counterpartyAccount
                ),
                new PatternDeduplicator.PatternGroup(
                        "WYPLATA",
                        "Wypłata pensji",
                        null, // sampleMerchant
                        null, // averageMerchantConfidence
                        "Wynagrodzenie za grudzień",
                        Type.INFLOW,
                        1,
                        new BigDecimal("8500.00"),
                        "Przychody",
                        List.of("tx2"),
                        null // counterpartyAccount
                )
        );

        // when
        String prompt = promptBuilder.buildUserPrompt(patterns, structure, List.of(), "pl");

        // then - should separate OUTFLOW and INFLOW patterns
        assertThat(prompt).contains("OUTFLOW PATTERNS");
        assertThat(prompt).contains("INFLOW PATTERNS");
        assertThat(prompt).contains("NETFLIX");
        assertThat(prompt).contains("WYPLATA");
    }

    @Test
    @DisplayName("Should handle empty category structure gracefully")
    void shouldHandleEmptyCategoryStructureGracefully() {
        // given
        CategoryStructure structure = CategoryStructure.empty();
        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "TEST",
                        "Test transaction",
                        null, // sampleMerchant
                        null, // averageMerchantConfidence
                        "",
                        Type.OUTFLOW,
                        1,
                        new BigDecimal("10.00"),
                        "",
                        List.of("tx1"),
                        null // counterpartyAccount
                )
        );

        // when
        String prompt = promptBuilder.buildUserPrompt(patterns, structure, List.of(), "pl");

        // then - should not crash and should not include empty sections
        assertThat(prompt).doesNotContain("INFLOW (przychody):");
        assertThat(prompt).doesNotContain("OUTFLOW (wydatki):");
        assertThat(prompt).contains("TEST");
    }

    @Test
    @DisplayName("Should include sample description (title) in pattern output")
    void shouldIncludeSampleDescriptionInPatternOutput() {
        // given
        CategoryStructure structure = CategoryStructure.empty();
        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "SILVA",
                        "Silva Martins",
                        null, // sampleMerchant
                        null, // averageMerchantConfidence
                        "Czynsz za styczeń 2026",
                        Type.OUTFLOW,
                        1,
                        new BigDecimal("2500.00"),
                        "Przelewy wychodzące",
                        List.of("tx1"),
                        null // counterpartyAccount
                )
        );

        // when
        String prompt = promptBuilder.buildUserPrompt(patterns, structure, List.of(), "pl");

        // then - should include title from description
        assertThat(prompt).contains("title:");
        assertThat(prompt).contains("Czynsz za styczeń 2026");
    }
}
