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
        // given
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
                  "patternMappings": []
                }
                """;

        // when
        AiCategorizationResponseParser.ParseResult result = parser.parse(aiResponse, List.of());

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.structure().outflow()).hasSize(2);
        assertThat(result.structure().outflow().get(0).name()).isEqualTo("Żywność");
        assertThat(result.structure().outflow().get(0).subCategories())
                .containsExactly("Zakupy spożywcze", "Restauracje");
        assertThat(result.structure().inflow()).hasSize(1);
        assertThat(result.structure().inflow().get(0).name()).isEqualTo("Wynagrodzenie");
    }
}
