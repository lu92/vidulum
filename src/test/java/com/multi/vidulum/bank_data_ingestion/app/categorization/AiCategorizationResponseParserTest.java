package com.multi.vidulum.bank_data_ingestion.app.categorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.bank_data_ingestion.domain.AiCategorizationResult;
import com.multi.vidulum.cashflow.domain.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AiCategorizationResponseParser.
 * Verifies parsing of new fields: isExistingCategory, reason, unrecognizedPatterns.
 */
class AiCategorizationResponseParserTest {

    private AiCategorizationResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new AiCategorizationResponseParser(new ObjectMapper());
    }

    @Test
    @DisplayName("Should parse response with isExistingCategory and reason fields")
    void shouldParseResponseWithIsExistingCategoryAndReasonFields() {
        // given
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [],
                    "inflow": []
                  },
                  "patternMappings": [
                    {
                      "pattern": "BIEDRONKA",
                      "suggestedCategory": "Zakupy spożywcze",
                      "parentCategory": "Żywność",
                      "type": "OUTFLOW",
                      "confidence": 95,
                      "isExistingCategory": true,
                      "reason": "Matches existing category under Żywność"
                    }
                  ]
                }
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "BIEDRONKA",
                        "Biedronka zakupy",
                        "",
                        Type.OUTFLOW,
                        5,
                        new BigDecimal("250.00"),
                        "",
                        List.of("tx1", "tx2", "tx3", "tx4", "tx5")
                )
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.suggestions()).hasSize(1);

        AiCategorizationResult.PatternSuggestion suggestion = result.suggestions().get(0);
        assertThat(suggestion.pattern()).isEqualTo("BIEDRONKA");
        assertThat(suggestion.suggestedCategory()).isEqualTo("Zakupy spożywcze");
        assertThat(suggestion.isExistingCategory()).isTrue();
        assertThat(suggestion.reason()).isEqualTo("Matches existing category under Żywność");
    }

    @Test
    @DisplayName("Should parse response with unrecognizedPatterns")
    void shouldParseResponseWithUnrecognizedPatterns() {
        // given
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [],
                    "inflow": []
                  },
                  "patternMappings": [
                    {
                      "pattern": "BIEDRONKA",
                      "suggestedCategory": "Zakupy",
                      "parentCategory": null,
                      "type": "OUTFLOW",
                      "confidence": 90
                    }
                  ],
                  "unrecognizedPatterns": [
                    {
                      "pattern": "XYZ123ABC",
                      "type": "OUTFLOW",
                      "reason": "Cryptic identifier, cannot determine category"
                    },
                    {
                      "pattern": "UNKNOWN_REF_999",
                      "type": "INFLOW",
                      "reason": "Reference number without context"
                    }
                  ]
                }
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "BIEDRONKA",
                        "Biedronka", "",
                        Type.OUTFLOW,
                        1, new BigDecimal("50.00"),
                        "", List.of("tx1")
                ),
                new PatternDeduplicator.PatternGroup(
                        "XYZ123ABC",
                        "XYZ123ABC", "",
                        Type.OUTFLOW,
                        2, new BigDecimal("100.00"),
                        "", List.of("tx2", "tx3")
                ),
                new PatternDeduplicator.PatternGroup(
                        "UNKNOWN_REF_999",
                        "Unknown ref", "",
                        Type.INFLOW,
                        1, new BigDecimal("500.00"),
                        "", List.of("tx4")
                )
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.unrecognizedPatterns()).hasSize(2);

        // Verify unrecognized patterns
        AiCategorizationResult.UnrecognizedPattern unrecog1 = result.unrecognizedPatterns().get(0);
        assertThat(unrecog1.pattern()).isEqualTo("XYZ123ABC");
        assertThat(unrecog1.type()).isEqualTo(Type.OUTFLOW);
        assertThat(unrecog1.reason()).isEqualTo("Cryptic identifier, cannot determine category");
        assertThat(unrecog1.transactionCount()).isEqualTo(2); // from pattern groups

        AiCategorizationResult.UnrecognizedPattern unrecog2 = result.unrecognizedPatterns().get(1);
        assertThat(unrecog2.pattern()).isEqualTo("UNKNOWN_REF_999");
        assertThat(unrecog2.type()).isEqualTo(Type.INFLOW);
    }

    @Test
    @DisplayName("Should handle missing isExistingCategory field (default to false)")
    void shouldHandleMissingIsExistingCategoryField() {
        // given
        String aiResponse = """
                {
                  "categoryStructure": {"outflow": [], "inflow": []},
                  "patternMappings": [
                    {
                      "pattern": "TEST",
                      "suggestedCategory": "Inne",
                      "type": "OUTFLOW",
                      "confidence": 70
                    }
                  ]
                }
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "TEST",
                        "Test", "",
                        Type.OUTFLOW,
                        1, new BigDecimal("10.00"),
                        "", List.of("tx1")
                )
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().get(0).isExistingCategory()).isFalse();
        assertThat(result.suggestions().get(0).reason()).isNull();
    }

    @Test
    @DisplayName("Should handle missing unrecognizedPatterns field")
    void shouldHandleMissingUnrecognizedPatternsField() {
        // given
        String aiResponse = """
                {
                  "categoryStructure": {"outflow": [], "inflow": []},
                  "patternMappings": []
                }
                """;

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, List.of());

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.unrecognizedPatterns()).isEmpty();
    }

    @Test
    @DisplayName("Should handle markdown code blocks in response")
    void shouldHandleMarkdownCodeBlocksInResponse() {
        // given
        String aiResponse = """
                ```json
                {
                  "categoryStructure": {"outflow": [], "inflow": []},
                  "patternMappings": [
                    {
                      "pattern": "TEST",
                      "suggestedCategory": "Test Category",
                      "type": "OUTFLOW",
                      "confidence": 85,
                      "isExistingCategory": true,
                      "reason": "Test reason"
                    }
                  ]
                }
                ```
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "TEST",
                        "Test", "",
                        Type.OUTFLOW,
                        1, new BigDecimal("10.00"),
                        "", List.of("tx1")
                )
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().get(0).isExistingCategory()).isTrue();
    }

    @Test
    @DisplayName("Should return error for invalid JSON")
    void shouldReturnErrorForInvalidJson() {
        // given
        String invalidJson = "not valid json {";

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(invalidJson, List.of());

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("JSON parse error");
    }

    @Test
    @DisplayName("Should return error for empty response")
    void shouldReturnErrorForEmptyResponse() {
        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse("", List.of());

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("Empty AI response");
    }

    @Test
    @DisplayName("Should parse category structure with hierarchy")
    void shouldParseCategoryStructureWithHierarchy() {
        // given - 2+ children means hierarchy is kept
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [
                      {
                        "name": "Żywność",
                        "subCategories": ["Zakupy spożywcze", "Restauracje"]
                      },
                      {
                        "name": "Transport",
                        "subCategories": ["Paliwo", "Komunikacja miejska"]
                      }
                    ],
                    "inflow": [
                      {
                        "name": "Wynagrodzenie",
                        "subCategories": ["Pensja", "Premia"]
                      }
                    ]
                  },
                  "patternMappings": [
                    {"pattern": "BIEDRONKA", "suggestedCategory": "Zakupy spożywcze", "parentCategory": "Żywność", "type": "OUTFLOW", "confidence": 90},
                    {"pattern": "RESTAURACJA", "suggestedCategory": "Restauracje", "parentCategory": "Żywność", "type": "OUTFLOW", "confidence": 90},
                    {"pattern": "ORLEN", "suggestedCategory": "Paliwo", "parentCategory": "Transport", "type": "OUTFLOW", "confidence": 90},
                    {"pattern": "ZTM", "suggestedCategory": "Komunikacja miejska", "parentCategory": "Transport", "type": "OUTFLOW", "confidence": 90},
                    {"pattern": "PENSJA", "suggestedCategory": "Pensja", "parentCategory": "Wynagrodzenie", "type": "INFLOW", "confidence": 95},
                    {"pattern": "PREMIA", "suggestedCategory": "Premia", "parentCategory": "Wynagrodzenie", "type": "INFLOW", "confidence": 95}
                  ]
                }
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup("BIEDRONKA", "Biedronka", "", Type.OUTFLOW, 3, new BigDecimal("150.00"), "", List.of()),
                new PatternDeduplicator.PatternGroup("RESTAURACJA", "Restauracja", "", Type.OUTFLOW, 2, new BigDecimal("100.00"), "", List.of()),
                new PatternDeduplicator.PatternGroup("ORLEN", "Orlen", "", Type.OUTFLOW, 4, new BigDecimal("400.00"), "", List.of()),
                new PatternDeduplicator.PatternGroup("ZTM", "ZTM", "", Type.OUTFLOW, 5, new BigDecimal("50.00"), "", List.of()),
                new PatternDeduplicator.PatternGroup("PENSJA", "Pensja", "", Type.INFLOW, 1, new BigDecimal("5000.00"), "", List.of()),
                new PatternDeduplicator.PatternGroup("PREMIA", "Premia", "", Type.INFLOW, 1, new BigDecimal("1000.00"), "", List.of())
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.structure().outflow()).hasSize(2);
        assertThat(result.structure().outflow().get(0).name()).isEqualTo("Żywność");
        assertThat(result.structure().outflow().get(0).subCategories())
                .containsExactly("Zakupy spożywcze", "Restauracje");
        assertThat(result.structure().inflow()).hasSize(1);
        assertThat(result.structure().inflow().get(0).name()).isEqualTo("Wynagrodzenie");
    }

    // ============ NEW TESTS FOR POST-PROCESSING ============

    @Test
    @DisplayName("Should flatten single-child hierarchy - promote child to root level")
    void shouldFlattenSingleChildHierarchy() {
        // given - "Żywność" has only 1 child "Sklepy spożywcze" → should be flattened
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [
                      {
                        "name": "Żywność",
                        "subCategories": ["Sklepy spożywcze"]
                      }
                    ],
                    "inflow": []
                  },
                  "patternMappings": [
                    {"pattern": "BIEDRONKA", "suggestedCategory": "Sklepy spożywcze", "parentCategory": "Żywność", "type": "OUTFLOW", "confidence": 90}
                  ]
                }
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "BIEDRONKA", "Biedronka zakupy", "", Type.OUTFLOW,
                        5, new BigDecimal("250.00"), "", List.of()
                )
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();

        // "Żywność" should be removed, "Sklepy spożywcze" should be promoted to root
        assertThat(result.structure().outflow()).hasSize(1);
        assertThat(result.structure().outflow().get(0).name()).isEqualTo("Sklepy spożywcze");
        assertThat(result.structure().outflow().get(0).subCategories()).isEmpty();
    }

    @Test
    @DisplayName("Should keep hierarchy when parent has 2+ children")
    void shouldKeepHierarchyWithMultipleChildren() {
        // given - "Żywność" has 2 children → should NOT be flattened
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [
                      {
                        "name": "Żywność",
                        "subCategories": ["Sklepy spożywcze", "Restauracje"]
                      }
                    ],
                    "inflow": []
                  },
                  "patternMappings": [
                    {"pattern": "BIEDRONKA", "suggestedCategory": "Sklepy spożywcze", "parentCategory": "Żywność", "type": "OUTFLOW", "confidence": 90},
                    {"pattern": "PIZZERIA", "suggestedCategory": "Restauracje", "parentCategory": "Żywność", "type": "OUTFLOW", "confidence": 85}
                  ]
                }
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup("BIEDRONKA", "Biedronka", "", Type.OUTFLOW, 5, new BigDecimal("250.00"), "", List.of()),
                new PatternDeduplicator.PatternGroup("PIZZERIA", "Pizzeria", "", Type.OUTFLOW, 3, new BigDecimal("150.00"), "", List.of())
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.structure().outflow()).hasSize(1);
        assertThat(result.structure().outflow().get(0).name()).isEqualTo("Żywność");
        assertThat(result.structure().outflow().get(0).subCategories())
                .containsExactly("Sklepy spożywcze", "Restauracje");
    }

    @Test
    @DisplayName("Should remove categories with zero transactions")
    void shouldRemoveCategoriesWithZeroTransactions() {
        // given - "Wynagrodzenie" has NO pattern mappings → should be removed
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [
                      {
                        "name": "Żywność",
                        "subCategories": ["Sklepy spożywcze", "Restauracje"]
                      }
                    ],
                    "inflow": [
                      {
                        "name": "Wynagrodzenie",
                        "subCategories": ["Pensja"]
                      }
                    ]
                  },
                  "patternMappings": [
                    {"pattern": "BIEDRONKA", "suggestedCategory": "Sklepy spożywcze", "parentCategory": "Żywność", "type": "OUTFLOW", "confidence": 90},
                    {"pattern": "PIZZERIA", "suggestedCategory": "Restauracje", "parentCategory": "Żywność", "type": "OUTFLOW", "confidence": 85}
                  ]
                }
                """;

        // Note: No INFLOW patterns - "Wynagrodzenie" has 0 transactions
        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup("BIEDRONKA", "Biedronka", "", Type.OUTFLOW, 5, new BigDecimal("250.00"), "", List.of()),
                new PatternDeduplicator.PatternGroup("PIZZERIA", "Pizzeria", "", Type.OUTFLOW, 3, new BigDecimal("150.00"), "", List.of())
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();

        // OUTFLOW should be kept (has transactions)
        assertThat(result.structure().outflow()).hasSize(1);
        assertThat(result.structure().outflow().get(0).name()).isEqualTo("Żywność");

        // INFLOW should be empty (no transactions for "Wynagrodzenie")
        assertThat(result.structure().inflow()).isEmpty();
    }

    @Test
    @DisplayName("Should calculate transaction count from pattern mappings")
    void shouldCalculateTransactionCountFromPatternMappings() {
        // given
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [
                      {
                        "name": "Zakupy",
                        "subCategories": []
                      }
                    ],
                    "inflow": []
                  },
                  "patternMappings": [
                    {"pattern": "BIEDRONKA", "suggestedCategory": "Zakupy", "type": "OUTFLOW", "confidence": 90},
                    {"pattern": "LIDL", "suggestedCategory": "Zakupy", "type": "OUTFLOW", "confidence": 85}
                  ]
                }
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup("BIEDRONKA", "Biedronka", "", Type.OUTFLOW, 5, new BigDecimal("250.00"), "", List.of()),
                new PatternDeduplicator.PatternGroup("LIDL", "Lidl", "", Type.OUTFLOW, 3, new BigDecimal("150.00"), "", List.of())
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.structure().outflow()).hasSize(1);

        AiCategorizationResult.CategoryNode zakupy = result.structure().outflow().get(0);
        assertThat(zakupy.name()).isEqualTo("Zakupy");
        assertThat(zakupy.transactionCount()).isEqualTo(8); // 5 + 3
        assertThat(zakupy.totalAmount()).isEqualByComparingTo(new BigDecimal("400.00")); // 250 + 150
    }

    @Test
    @DisplayName("Should filter out empty subcategories and then flatten if only 1 child remains")
    void shouldFilterOutEmptySubcategoriesAndFlattenIfOnlyOneChildRemains() {
        // given - "Żywność" has 2 subcategories but only 1 has transactions
        // After filtering "Restauracje" (0 transactions), only 1 child remains → should be flattened
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [
                      {
                        "name": "Żywność",
                        "subCategories": ["Sklepy spożywcze", "Restauracje"]
                      }
                    ],
                    "inflow": []
                  },
                  "patternMappings": [
                    {"pattern": "BIEDRONKA", "suggestedCategory": "Sklepy spożywcze", "parentCategory": "Żywność", "type": "OUTFLOW", "confidence": 90}
                  ]
                }
                """;

        // Only "Sklepy spożywcze" has transactions, "Restauracje" has 0
        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup("BIEDRONKA", "Biedronka", "", Type.OUTFLOW, 5, new BigDecimal("250.00"), "", List.of())
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();

        // Processing order: 1) Filter (removes "Restauracje"), 2) Flatten (1 child → promote)
        // Result: "Sklepy spożywcze" as root category (no parent "Żywność")
        assertThat(result.structure().outflow()).hasSize(1);
        assertThat(result.structure().outflow().get(0).name()).isEqualTo("Sklepy spożywcze");
        assertThat(result.structure().outflow().get(0).subCategories()).isEmpty();
    }

    @Test
    @DisplayName("Should handle empty category structure")
    void shouldHandleEmptyCategoryStructure() {
        // given
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [],
                    "inflow": []
                  },
                  "patternMappings": []
                }
                """;

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, List.of());

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.structure().outflow()).isEmpty();
        assertThat(result.structure().inflow()).isEmpty();
    }

    @Test
    @DisplayName("Should count bank category suggestion transactions")
    void shouldCountBankCategorySuggestionTransactions() {
        // given
        String aiResponse = """
                {
                  "categoryStructure": {
                    "outflow": [
                      {"name": "Opłaty", "subCategories": []}
                    ],
                    "inflow": []
                  },
                  "patternMappings": [],
                  "bankCategoryMappings": [
                    {"bankCategory": "Przelewy wychodzące", "targetCategory": "Opłaty", "type": "OUTFLOW", "confidence": 80}
                  ]
                }
                """;

        // Bank category groups
        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup("TX1", "Transfer 1", "", Type.OUTFLOW, 3, new BigDecimal("300.00"), "Przelewy wychodzące", List.of()),
                new PatternDeduplicator.PatternGroup("TX2", "Transfer 2", "", Type.OUTFLOW, 2, new BigDecimal("200.00"), "Przelewy wychodzące", List.of())
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.structure().outflow()).hasSize(1);

        AiCategorizationResult.CategoryNode oplaty = result.structure().outflow().get(0);
        assertThat(oplaty.name()).isEqualTo("Opłaty");
        assertThat(oplaty.transactionCount()).isEqualTo(5); // 3 + 2 from bank category
    }

    // ============ TESTS FOR STRATEGY C: bankCategoryMappings VALIDATION ============

    @Test
    @DisplayName("Should filter out bankCategoryMappings with invalid bankCategory (merchant name instead of actual bankCategory)")
    void shouldFilterOutInvalidBankCategoryMappings() {
        // given - AI incorrectly used "ZABKA" as bankCategory (should be "TRANSAKCJA KARTĄ PŁATNICZĄ")
        // This is a common AI mistake where it confuses merchant names with bankCategories
        String aiResponse = """
                {
                  "categoryStructure": {"outflow": [], "inflow": []},
                  "patternMappings": [
                    {"pattern": "ZABKA", "suggestedCategory": "Zakupy", "type": "OUTFLOW", "confidence": 90}
                  ],
                  "bankCategoryMappings": [
                    {"bankCategory": "ZABKA", "targetCategory": "Zakupy", "type": "OUTFLOW", "confidence": 80},
                    {"bankCategory": "TRANSAKCJA KARTĄ PŁATNICZĄ", "targetCategory": "Inne", "type": "OUTFLOW", "confidence": 70}
                  ]
                }
                """;

        // Actual data has "TRANSAKCJA KARTĄ PŁATNICZĄ" as bankCategory, not "ZABKA"
        // ZABKA is just a merchant name (pattern), not a bankCategory
        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup(
                        "ZABKA", "Zabka zakupy", "",
                        Type.OUTFLOW, 5, new BigDecimal("100.00"),
                        "TRANSAKCJA KARTĄ PŁATNICZĄ",  // ← actual bankCategory from bank
                        List.of()
                )
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        // Only valid mapping should remain (TRANSAKCJA KARTĄ PŁATNICZĄ)
        // "ZABKA" mapping should be filtered out because ZABKA is not in actual bankCategories
        assertThat(result.bankCategorySuggestions()).hasSize(1);
        assertThat(result.bankCategorySuggestions().get(0).bankCategory())
                .isEqualTo("TRANSAKCJA KARTĄ PŁATNICZĄ");
    }

    @Test
    @DisplayName("Should keep all bankCategoryMappings when all reference actual bankCategories")
    void shouldKeepAllValidBankCategoryMappings() {
        // given - all bankCategoryMappings reference actual bankCategories from the data
        String aiResponse = """
                {
                  "categoryStructure": {"outflow": [], "inflow": []},
                  "patternMappings": [],
                  "bankCategoryMappings": [
                    {"bankCategory": "PRZELEW", "targetCategory": "Przelewy", "type": "OUTFLOW", "confidence": 85},
                    {"bankCategory": "WPŁYWY REGULARNE", "targetCategory": "Wynagrodzenie", "type": "INFLOW", "confidence": 90}
                  ]
                }
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup("TX1", "Transfer", "", Type.OUTFLOW, 3, new BigDecimal("300.00"), "PRZELEW", List.of()),
                new PatternDeduplicator.PatternGroup("TX2", "Salary", "", Type.INFLOW, 1, new BigDecimal("5000.00"), "WPŁYWY REGULARNE", List.of())
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.bankCategorySuggestions()).hasSize(2);
    }

    @Test
    @DisplayName("Should handle empty bankCategoryMappings in validation")
    void shouldHandleEmptyBankCategoryMappingsInValidation() {
        // given
        String aiResponse = """
                {
                  "categoryStructure": {"outflow": [], "inflow": []},
                  "patternMappings": [],
                  "bankCategoryMappings": []
                }
                """;

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, List.of());

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.bankCategorySuggestions()).isEmpty();
    }

    @Test
    @DisplayName("Should filter multiple invalid bankCategoryMappings (merchant names)")
    void shouldFilterMultipleInvalidBankCategoryMappings() {
        // given - AI made multiple mistakes: ZABKA, NETFLIX, ORLEN are merchant names, not bankCategories
        String aiResponse = """
                {
                  "categoryStructure": {"outflow": [], "inflow": []},
                  "patternMappings": [],
                  "bankCategoryMappings": [
                    {"bankCategory": "ZABKA", "targetCategory": "Zakupy", "type": "OUTFLOW", "confidence": 80},
                    {"bankCategory": "NETFLIX", "targetCategory": "Rozrywka", "type": "OUTFLOW", "confidence": 80},
                    {"bankCategory": "ORLEN", "targetCategory": "Paliwo", "type": "OUTFLOW", "confidence": 80},
                    {"bankCategory": "TRANSAKCJA KARTĄ PŁATNICZĄ", "targetCategory": "Inne", "type": "OUTFLOW", "confidence": 70}
                  ]
                }
                """;

        // All transactions have the same generic bankCategory
        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup("ZABKA", "Zabka", "", Type.OUTFLOW, 5, new BigDecimal("100.00"), "TRANSAKCJA KARTĄ PŁATNICZĄ", List.of()),
                new PatternDeduplicator.PatternGroup("NETFLIX", "Netflix", "", Type.OUTFLOW, 3, new BigDecimal("50.00"), "TRANSAKCJA KARTĄ PŁATNICZĄ", List.of()),
                new PatternDeduplicator.PatternGroup("ORLEN", "Orlen", "", Type.OUTFLOW, 4, new BigDecimal("400.00"), "TRANSAKCJA KARTĄ PŁATNICZĄ", List.of())
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        // Only 1 valid mapping should remain
        assertThat(result.bankCategorySuggestions()).hasSize(1);
        assertThat(result.bankCategorySuggestions().get(0).bankCategory())
                .isEqualTo("TRANSAKCJA KARTĄ PŁATNICZĄ");
    }

    @Test
    @DisplayName("Should validate bankCategory case-insensitively")
    void shouldValidateBankCategoryCaseInsensitively() {
        // given - AI returns lowercase, actual data has uppercase
        String aiResponse = """
                {
                  "categoryStructure": {"outflow": [], "inflow": []},
                  "patternMappings": [],
                  "bankCategoryMappings": [
                    {"bankCategory": "przelew", "targetCategory": "Przelewy", "type": "OUTFLOW", "confidence": 85}
                  ]
                }
                """;

        List<PatternDeduplicator.PatternGroup> patterns = List.of(
                new PatternDeduplicator.PatternGroup("TX1", "Transfer", "", Type.OUTFLOW, 3, new BigDecimal("300.00"), "PRZELEW", List.of())
        );

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, patterns);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.bankCategorySuggestions()).hasSize(1); // should match case-insensitively
    }
}
