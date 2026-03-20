package com.multi.vidulum.bank_data_adapter.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes AI response and extracts CSV content and metadata.
 */
@Slf4j
@Component
public class AiResponseProcessor {

    private static final String EXPECTED_HEADER = "bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber";

    private static final Pattern DETECTED_BANK_PATTERN = Pattern.compile("# DETECTED_BANK: (.+)");
    private static final Pattern DETECTED_LANGUAGE_PATTERN = Pattern.compile("# DETECTED_LANGUAGE: (.+)");
    private static final Pattern DETECTED_COUNTRY_PATTERN = Pattern.compile("# DETECTED_COUNTRY: (.+)");
    private static final Pattern ROW_COUNT_PATTERN = Pattern.compile("# ROW_COUNT: (\\d+)");
    private static final Pattern WARNINGS_PATTERN = Pattern.compile("# WARNINGS: (.+)");

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("ERROR: (\\w+)");
    private static final Pattern ERROR_MESSAGE_PATTERN = Pattern.compile("MESSAGE: (.+)");

    public AiTransformResult process(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return AiTransformResult.error(AiErrorCode.EMPTY_RESPONSE, "AI returned empty response");
        }

        String trimmed = aiResponse.trim();

        // Check for error response
        if (trimmed.startsWith("ERROR:")) {
            return parseErrorResponse(trimmed);
        }

        // Check if response starts with expected CSV header
        if (!trimmed.startsWith("bankTransactionId,")) {
            log.warn("AI response does not start with expected header. First 100 chars: {}",
                trimmed.substring(0, Math.min(100, trimmed.length())));
            return AiTransformResult.error(AiErrorCode.INVALID_RESPONSE,
                "AI response is not valid CSV - expected header row starting with 'bankTransactionId,'");
        }

        // Split CSV content from metadata
        String[] parts = trimmed.split("\n\n# DETECTED_BANK:", 2);
        String csvContent = parts[0].trim();
        String metadata = parts.length > 1 ? "# DETECTED_BANK:" + parts[1] : "";

        // Validate CSV structure
        String[] lines = csvContent.split("\n");
        if (lines.length < 2) {
            return AiTransformResult.error(AiErrorCode.INVALID_RESPONSE, "CSV has no data rows");
        }

        // Validate header
        String header = lines[0].trim();
        if (!header.equals(EXPECTED_HEADER)) {
            log.warn("Header mismatch. Expected: {}, Got: {}", EXPECTED_HEADER, header);
            // Allow it but log warning - AI might have slightly different spacing
        }

        // Count data rows (excluding header)
        int rowCount = lines.length - 1;

        // Extract metadata
        String detectedBank = extractMetadata(metadata, DETECTED_BANK_PATTERN, "unknown");
        String detectedLanguage = extractMetadata(metadata, DETECTED_LANGUAGE_PATTERN, "unknown");
        String detectedCountry = extractMetadata(metadata, DETECTED_COUNTRY_PATTERN, "unknown");
        String warningsStr = extractMetadata(metadata, WARNINGS_PATTERN, "none");

        // Parse row count from metadata if available
        String rowCountStr = extractMetadata(metadata, ROW_COUNT_PATTERN, null);
        if (rowCountStr != null) {
            try {
                int metadataRowCount = Integer.parseInt(rowCountStr);
                if (metadataRowCount != rowCount) {
                    log.warn("Row count mismatch: metadata says {}, actual is {}", metadataRowCount, rowCount);
                }
            } catch (NumberFormatException e) {
                // Ignore
            }
        }

        // Parse warnings
        List<String> warnings = new ArrayList<>();
        if (!"none".equalsIgnoreCase(warningsStr) && !warningsStr.isBlank()) {
            for (String warning : warningsStr.split(",")) {
                warnings.add(warning.trim());
            }
        }

        log.info("Successfully processed AI response: bank={}, language={}, country={}, rows={}",
            detectedBank, detectedLanguage, detectedCountry, rowCount);

        if (warnings.isEmpty()) {
            return AiTransformResult.success(csvContent, detectedBank, detectedLanguage, detectedCountry, rowCount);
        } else {
            return AiTransformResult.successWithWarnings(csvContent, detectedBank, detectedLanguage, detectedCountry, rowCount, warnings);
        }
    }

    private AiTransformResult parseErrorResponse(String response) {
        Matcher codeMatcher = ERROR_CODE_PATTERN.matcher(response);
        Matcher messageMatcher = ERROR_MESSAGE_PATTERN.matcher(response);

        String code = codeMatcher.find() ? codeMatcher.group(1) : "UNKNOWN";
        String message = messageMatcher.find() ? messageMatcher.group(1) : "Unknown error";

        AiErrorCode errorCode;
        try {
            errorCode = AiErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            errorCode = AiErrorCode.UNRECOGNIZED_FORMAT;
        }

        return AiTransformResult.error(errorCode, message);
    }

    private String extractMetadata(String metadata, Pattern pattern, String defaultValue) {
        Matcher matcher = pattern.matcher(metadata);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return defaultValue;
    }
}
