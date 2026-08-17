package com.multi.vidulum.bank_data_adapter.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AiMappingRulesProcessor delimiter validation.
 * Only delimiter is validated against pre-detection - AI determines the rest.
 */
class AiMappingRulesProcessorValidationTest {

    private AiMappingRulesProcessor processor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        processor = new AiMappingRulesProcessor(objectMapper);
    }

    @Nested
    @DisplayName("Delimiter Validation and Override")
    class DelimiterValidation {

        @Test
        @DisplayName("Should override AI delimiter when detection confidence is high")
        void shouldOverrideDelimiterWhenHighConfidence() {
            // Given - AI returns comma but statistical analysis found semicolon with high confidence
            String aiResponse = """
                {
                    "bankName": "Test Bank",
                    "delimiter": ",",
                    "headerRowIndex": 0,
                    "dateFormat": "dd.MM.yyyy",
                    "columnMappings": []
                }
                """;

            CsvFormatDetector.DetectedDelimiter detectedDelimiter = new CsvFormatDetector.DetectedDelimiter(
                ";",      // Statistically detected semicolon
                0.9       // High confidence (90%)
            );

            // When
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123", detectedDelimiter);

            // Then
            assertThat(result.success()).isTrue();
            assertThat(result.rules().getDelimiter()).isEqualTo(";");
            assertThat(result.rules().getWarnings())
                .anyMatch(w -> w.contains("Delimiter corrected"));
        }

        @Test
        @DisplayName("Should NOT override AI delimiter when detection confidence is low")
        void shouldNotOverrideDelimiterWhenLowConfidence() {
            // Given - AI returns comma and detection found semicolon with LOW confidence
            String aiResponse = """
                {
                    "bankName": "Test Bank",
                    "delimiter": ",",
                    "headerRowIndex": 0,
                    "dateFormat": "dd.MM.yyyy",
                    "columnMappings": []
                }
                """;

            CsvFormatDetector.DetectedDelimiter detectedDelimiter = new CsvFormatDetector.DetectedDelimiter(
                ";",      // Detected semicolon
                0.5       // Low confidence (50%)
            );

            // When
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123", detectedDelimiter);

            // Then - AI value should be kept (not overridden)
            assertThat(result.success()).isTrue();
            assertThat(result.rules().getDelimiter()).isEqualTo(",");
        }

        @Test
        @DisplayName("Should keep AI delimiter when it matches detection")
        void shouldKeepDelimiterWhenMatching() {
            // Given - AI and detection both return semicolon
            String aiResponse = """
                {
                    "bankName": "Test Bank",
                    "delimiter": ";",
                    "headerRowIndex": 0,
                    "dateFormat": "dd.MM.yyyy",
                    "columnMappings": []
                }
                """;

            CsvFormatDetector.DetectedDelimiter detectedDelimiter = new CsvFormatDetector.DetectedDelimiter(
                ";",      // Same as AI
                0.9
            );

            // When
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123", detectedDelimiter);

            // Then - No warnings about delimiter
            assertThat(result.success()).isTrue();
            assertThat(result.rules().getDelimiter()).isEqualTo(";");
            assertThat(result.rules().getWarnings())
                .noneMatch(w -> w.contains("Delimiter"));
        }
    }

    @Nested
    @DisplayName("AI-Determined Values (Not Validated)")
    class AiDeterminedValues {

        @Test
        @DisplayName("Should trust AI for headerRowIndex (not validated)")
        void shouldTrustAiForHeaderRowIndex() {
            // Given - AI returns headerRowIndex=3 (metadata rows before header)
            String aiResponse = """
                {
                    "bankName": "Test Bank",
                    "delimiter": ";",
                    "headerRowIndex": 3,
                    "metadataRows": 3,
                    "dateFormat": "dd.MM.yyyy",
                    "columnMappings": []
                }
                """;

            CsvFormatDetector.DetectedDelimiter detectedDelimiter = new CsvFormatDetector.DetectedDelimiter(
                ";",
                0.85
            );

            // When
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123", detectedDelimiter);

            // Then - AI value should be used (not overridden)
            assertThat(result.success()).isTrue();
            assertThat(result.rules().getHeaderRowIndex()).isEqualTo(3);
            assertThat(result.rules().getMetadataRows()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should trust AI for dateFormat (not validated)")
        void shouldTrustAiForDateFormat() {
            // Given - AI returns dateFormat
            String aiResponse = """
                {
                    "bankName": "Test Bank",
                    "delimiter": ";",
                    "headerRowIndex": 0,
                    "dateFormat": "dd-MM-yyyy",
                    "columnMappings": []
                }
                """;

            CsvFormatDetector.DetectedDelimiter detectedDelimiter = new CsvFormatDetector.DetectedDelimiter(
                ";",
                0.9
            );

            // When
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123", detectedDelimiter);

            // Then - AI dateFormat should be used
            assertThat(result.success()).isTrue();
            assertThat(result.rules().getDateFormat()).isEqualTo("dd-MM-yyyy");
        }
    }

    @Nested
    @DisplayName("Processing Without Detection")
    class ProcessingWithoutDetection {

        @Test
        @DisplayName("Should process normally without detected delimiter")
        void shouldProcessWithoutDetectedDelimiter() {
            // Given
            String aiResponse = """
                {
                    "bankName": "Test Bank",
                    "delimiter": ",",
                    "headerRowIndex": 0,
                    "dateFormat": "yyyy-MM-dd",
                    "columnMappings": []
                }
                """;

            // When - No detected delimiter (null)
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123", null);

            // Then
            assertThat(result.success()).isTrue();
            assertThat(result.rules().getDelimiter()).isEqualTo(",");
            assertThat(result.rules().getDateFormat()).isEqualTo("yyyy-MM-dd");
        }

        @Test
        @DisplayName("Should process normally with backwards-compatible method")
        void shouldProcessWithBackwardsCompatibleMethod() {
            // Given
            String aiResponse = """
                {
                    "bankName": "Test Bank",
                    "delimiter": ";",
                    "headerRowIndex": 0,
                    "dateFormat": "dd.MM.yyyy",
                    "columnMappings": []
                }
                """;

            // When - Using old method without detected delimiter
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123");

            // Then
            assertThat(result.success()).isTrue();
            assertThat(result.rules().getDelimiter()).isEqualTo(";");
        }
    }

    @Nested
    @DisplayName("Confidence Score Blending")
    class ConfidenceScoreBlending {

        @Test
        @DisplayName("Should blend confidence scores when delimiter is corrected")
        void shouldBlendConfidenceScoresWhenCorrected() {
            // Given
            String aiResponse = """
                {
                    "bankName": "Test Bank",
                    "delimiter": ",",
                    "headerRowIndex": 0,
                    "dateFormat": "dd.MM.yyyy",
                    "confidenceScore": 0.95,
                    "columnMappings": []
                }
                """;

            CsvFormatDetector.DetectedDelimiter detectedDelimiter = new CsvFormatDetector.DetectedDelimiter(
                ";",      // Different delimiter - will be corrected
                0.85
            );

            // When
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123", detectedDelimiter);

            // Then - Confidence should be blended (0.95 + 0.85) / 2 = 0.9
            assertThat(result.success()).isTrue();
            assertThat(result.rules().getConfidenceScore()).isBetween(0.85, 0.95);
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should handle AI error response")
        void shouldHandleAiErrorResponse() {
            // Given
            String aiResponse = """
                {
                    "error": true,
                    "errorCode": "UNRECOGNIZED_FORMAT",
                    "errorMessage": "Could not parse CSV"
                }
                """;

            CsvFormatDetector.DetectedDelimiter detectedDelimiter = new CsvFormatDetector.DetectedDelimiter(
                ";", 0.9
            );

            // When
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123", detectedDelimiter);

            // Then
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("UNRECOGNIZED_FORMAT");
        }

        @Test
        @DisplayName("Should handle invalid JSON")
        void shouldHandleInvalidJson() {
            // Given
            String aiResponse = "This is not valid JSON";

            CsvFormatDetector.DetectedDelimiter detectedDelimiter = new CsvFormatDetector.DetectedDelimiter(
                ";", 0.9
            );

            // When
            AiMappingRulesProcessor.MappingRulesResult result =
                processor.process(aiResponse, "bank:123", detectedDelimiter);

            // Then
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("Invalid JSON");
        }
    }
}
