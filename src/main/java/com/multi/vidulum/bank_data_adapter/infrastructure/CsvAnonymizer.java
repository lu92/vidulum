package com.multi.vidulum.bank_data_adapter.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anonymizes sensitive data in CSV content before sending to AI.
 *
 * Privacy-first approach:
 * - Replaces personal names with placeholders
 * - Replaces IBAN/account numbers with masked versions
 * - Replaces addresses with generic placeholders
 * - Keeps amounts, dates, and transaction types intact (needed for transformation)
 *
 * Only sample rows (first N) are sent to AI to minimize exposure and cost.
 */
@Slf4j
@Component
public class CsvAnonymizer {

    // Patterns for sensitive data
    private static final Pattern IBAN_PATTERN = Pattern.compile(
        "([A-Z]{2}\\d{2}[A-Z0-9]{4,30}|\\d{26}|PL\\d{26})", Pattern.CASE_INSENSITIVE);

    private static final Pattern POLISH_ACCOUNT_PATTERN = Pattern.compile(
        "\\b\\d{2}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\b");

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "(\\+48|48)?\\s*\\d{3}\\s*\\d{3}\\s*\\d{3}");

    private static final Pattern PESEL_PATTERN = Pattern.compile(
        "\\b\\d{11}\\b");

    private static final Pattern NIP_PATTERN = Pattern.compile(
        "\\b\\d{3}-?\\d{3}-?\\d{2}-?\\d{2}\\b|\\b\\d{10}\\b");

    // Common Polish street patterns
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
        "(ul\\.|ulica|al\\.|aleja|pl\\.|plac|os\\.|osiedle)\\s+[A-ZĄĆĘŁŃÓŚŹŻ][a-ząćęłńóśźż]+\\s+\\d+[a-zA-Z]?(\\s*/\\s*\\d+)?",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Polish personal name pattern (simplified)
    private static final Pattern NAME_PATTERN = Pattern.compile(
        "\\b([A-ZĄĆĘŁŃÓŚŹŻ][a-ząćęłńóśźż]+\\s+){1,2}[A-ZĄĆĘŁŃÓŚŹŻ][a-ząćęłńóśźż]+\\b",
        Pattern.UNICODE_CASE);

    /**
     * Anonymizes CSV content by replacing sensitive data with placeholders.
     *
     * @param csvContent Original CSV content
     * @return Anonymized CSV content
     */
    public String anonymize(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return csvContent;
        }

        String result = csvContent;
        int replacements = 0;

        // Replace IBANs (keep first 4 and last 4 digits)
        result = replaceWithMask(result, IBAN_PATTERN, this::maskAccountNumber);

        // Replace Polish account numbers
        result = replaceWithMask(result, POLISH_ACCOUNT_PATTERN, this::maskAccountNumber);

        // Replace emails
        result = EMAIL_PATTERN.matcher(result).replaceAll("[EMAIL]");

        // Replace phone numbers
        result = PHONE_PATTERN.matcher(result).replaceAll("[PHONE]");

        // Replace PESEL
        result = PESEL_PATTERN.matcher(result).replaceAll("[PESEL]");

        // Replace NIP
        result = NIP_PATTERN.matcher(result).replaceAll("[NIP]");

        // Replace addresses
        result = ADDRESS_PATTERN.matcher(result).replaceAll("[ADDRESS]");

        log.debug("CSV anonymization completed");
        return result;
    }

    /**
     * Extracts sample rows from CSV for AI processing.
     * Returns header + first N data rows.
     *
     * @param csvContent Full CSV content
     * @param sampleSize Number of data rows to include (excluding header)
     * @return Sample CSV content
     */
    public String extractSample(String csvContent, int sampleSize) {
        if (csvContent == null || csvContent.isBlank()) {
            return csvContent;
        }

        String[] lines = csvContent.split("\n");
        if (lines.length <= sampleSize + 1) {
            // File is small enough, return as-is
            return csvContent;
        }

        List<String> sampleLines = new ArrayList<>();
        int dataRowsAdded = 0;
        boolean headerFound = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                // Keep empty lines before header
                if (!headerFound) {
                    sampleLines.add(line);
                }
                continue;
            }

            // Detect header row (contains typical bank CSV headers)
            if (!headerFound && isLikelyHeader(trimmed)) {
                headerFound = true;
                sampleLines.add(line);
                continue;
            }

            // Before header - keep metadata lines
            if (!headerFound) {
                sampleLines.add(line);
                continue;
            }

            // After header - collect sample data rows
            if (dataRowsAdded < sampleSize) {
                sampleLines.add(line);
                dataRowsAdded++;
            } else {
                break;
            }
        }

        log.debug("Extracted sample: {} lines from {} original lines", sampleLines.size(), lines.length);
        return String.join("\n", sampleLines);
    }

    /**
     * Anonymizes and extracts sample from CSV in one operation.
     *
     * @param csvContent Full CSV content
     * @param sampleSize Number of data rows to include
     * @return Anonymized sample CSV
     */
    public String anonymizeAndSample(String csvContent, int sampleSize) {
        String sample = extractSample(csvContent, sampleSize);
        return anonymize(sample);
    }

    private boolean isLikelyHeader(String line) {
        String lower = line.toLowerCase();
        return lower.contains("data") && (
            lower.contains("kwota") ||
            lower.contains("operacj") ||
            lower.contains("saldo") ||
            lower.contains("tytuł") ||
            lower.contains("kontrahent")
        ) ||
        lower.contains("date") && (
            lower.contains("amount") ||
            lower.contains("description") ||
            lower.contains("balance")
        );
    }

    private String replaceWithMask(String input, Pattern pattern, java.util.function.Function<String, String> masker) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String masked = masker.apply(matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String maskAccountNumber(String account) {
        // Remove spaces
        String clean = account.replaceAll("\\s", "");

        if (clean.length() < 8) {
            return "[ACCOUNT]";
        }

        // Keep first 4 and last 4 characters
        String prefix = clean.substring(0, 4);
        String suffix = clean.substring(clean.length() - 4);
        int maskedLength = clean.length() - 8;

        return prefix + "*".repeat(Math.max(maskedLength, 4)) + suffix;
    }
}
