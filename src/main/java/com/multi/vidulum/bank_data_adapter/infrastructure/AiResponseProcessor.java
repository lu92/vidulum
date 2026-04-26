package com.multi.vidulum.bank_data_adapter.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    private static final Set<String> VALID_ISO_CURRENCIES = Set.of(
            "PLN", "EUR", "USD", "GBP", "CHF", "CZK", "SEK", "NOK", "DKK", "HUF",
            "RON", "BGN", "HRK", "RUB", "UAH", "TRY", "JPY", "CNY", "AUD", "CAD",
            "NZD", "ZAR", "BRL", "MXN", "INR", "KRW", "SGD", "HKD", "THB", "ILS"
    );

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

        // Sanitize CSV: remove header rows parsed as data, fix invalid currencies
        csvContent = sanitizeCsvContent(csvContent);
        lines = csvContent.split("\n");

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

    /**
     * Sanitizes AI-generated CSV content:
     * - Removes header rows that AI mistakenly included as data
     * - Validates and fixes currency column
     */
    private String sanitizeCsvContent(String csvContent) {
        String[] lines = csvContent.split("\n");
        if (lines.length < 2) return csvContent;

        StringBuilder result = new StringBuilder(lines[0]).append("\n"); // keep CSV header
        int removed = 0;
        int currencyFixed = 0;
        String detectedCurrency = detectDominantCurrency(lines);

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] fields = parseCsvRow(line);

            // Skip rows that look like original CSV column headers (not data)
            if (isLikelyHeaderRow(fields)) {
                log.info("Sanitizer: skipping likely header row at line {}: {}", i, line.substring(0, Math.min(80, line.length())));
                removed++;
                continue;
            }

            // Fix invalid currency (column index 5)
            if (fields.length > 5) {
                String currency = fields[5].trim();
                if (!currency.isEmpty() && !VALID_ISO_CURRENCIES.contains(currency.toUpperCase())) {
                    log.warn("Sanitizer: invalid currency '{}' at line {}, replacing with '{}'", currency, i, detectedCurrency);
                    fields[5] = detectedCurrency;
                    currencyFixed++;
                    line = toCsvLine(fields);
                }
            }

            result.append(line).append("\n");
        }

        if (removed > 0 || currencyFixed > 0) {
            log.info("Sanitizer: removed {} header rows, fixed {} invalid currencies (dominant: {})",
                    removed, currencyFixed, detectedCurrency);
        }

        return result.toString().trim();
    }

    /**
     * Detects the most common valid currency in the CSV data.
     */
    private String detectDominantCurrency(String[] lines) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] fields = parseCsvRow(lines[i]);
            if (fields.length > 5) {
                String c = fields[5].trim().toUpperCase();
                if (VALID_ISO_CURRENCIES.contains(c)) {
                    counts.merge(c, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse("PLN");
    }

    /**
     * Checks if a row is likely a column header that was mistakenly processed as data.
     * A data row must have a parseable date and a numeric amount.
     */
    private boolean isLikelyHeaderRow(String[] fields) {
        if (fields.length < 8) return false;

        String amountField = fields.length > 4 ? fields[4].trim() : "";
        String dateField = fields.length > 7 ? fields[7].trim() : "";

        // A data row has a numeric amount and a YYYY-MM-DD date
        boolean hasNumericAmount = !amountField.isEmpty() && amountField.matches("[\\d.,]+");
        boolean hasParseableDate = !dateField.isEmpty() && dateField.matches("\\d{4}-\\d{2}-\\d{2}");

        // If NEITHER amount is numeric NOR date is parseable → likely header
        return !hasNumericAmount && !hasParseableDate;
    }

    /**
     * Parses a CSV row respecting quoted fields.
     */
    private String[] parseCsvRow(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    /**
     * Converts fields back to a CSV line, quoting fields that contain commas.
     */
    private String toCsvLine(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String field = fields[i];
            if (field.contains(",") || field.contains("\"")) {
                sb.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(field);
            }
        }
        return sb.toString();
    }
}
