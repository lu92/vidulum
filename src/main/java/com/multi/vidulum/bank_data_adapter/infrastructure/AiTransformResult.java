package com.multi.vidulum.bank_data_adapter.infrastructure;

import java.util.List;

/**
 * Result of AI CSV transformation.
 */
public record AiTransformResult(
    boolean success,
    String csvContent,           // Transformed CSV ready for CsvParserService
    String detectedBank,         // Detected bank name
    String detectedLanguage,     // Detected language (pl, de, en)
    String detectedCountry,      // Detected country (PL, DE, UK)
    int rowCount,                // Number of data rows
    List<String> warnings,       // Warnings from transformation
    AiError error                // Error details (null if success)
) {
    public static AiTransformResult success(String csv, String bank, String language, String country, int rows) {
        return new AiTransformResult(true, csv, bank, language, country, rows, List.of(), null);
    }

    public static AiTransformResult successWithWarnings(String csv, String bank, String language, String country, int rows, List<String> warnings) {
        return new AiTransformResult(true, csv, bank, language, country, rows, warnings, null);
    }

    public static AiTransformResult error(AiErrorCode code, String message) {
        return new AiTransformResult(false, null, null, null, null, 0, List.of(),
            new AiError(code, message));
    }
}
