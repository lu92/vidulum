package com.multi.vidulum.bank_data_adapter.infrastructure;

import com.multi.vidulum.bank_data_adapter.domain.MappingRules;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules.ColumnMapping;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules.TransformationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms bank CSV to BankCsvRow format using cached mapping rules.
 * No AI call needed - purely local transformation.
 *
 * This is used when we already have mapping rules for a known bank format.
 */
@Slf4j
@Component
public class LocalCsvTransformer {

    // BankCsvRow header (must match CsvParserService.HEADERS)
    private static final String OUTPUT_HEADER =
        "bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber,merchant,merchantConfidence,paymentMethod";

    /**
     * Transform full CSV using mapping rules.
     *
     * @param csvContent Full CSV content
     * @param rules Mapping rules for this bank format
     * @return Transformed CSV in BankCsvRow format
     */
    public TransformResult transform(String csvContent, MappingRules rules) {
        if (csvContent == null || csvContent.isBlank()) {
            return TransformResult.failure("Empty CSV content");
        }

        String[] lines = csvContent.split("\n");
        List<String> outputLines = new ArrayList<>();
        outputLines.add(OUTPUT_HEADER);

        List<String> warnings = new ArrayList<>();
        int successCount = 0;
        int errorCount = 0;
        int lineNumber = 0;

        // Skip metadata rows
        int dataStartRow = rules.getHeaderRowIndex() + 1;

        for (int i = 0; i < lines.length; i++) {
            lineNumber = i + 1;
            String line = lines[i].trim();

            // Skip empty lines and metadata/header rows
            if (line.isEmpty() || i <= rules.getHeaderRowIndex()) {
                continue;
            }

            try {
                String[] columns = parseCsvLine(line, rules.getDelimiter());
                String outputRow = transformRow(columns, rules, lineNumber);

                if (outputRow != null) {
                    outputLines.add(outputRow);
                    successCount++;
                }
            } catch (Exception e) {
                errorCount++;
                if (errorCount <= 5) {
                    warnings.add(String.format("Line %d: %s", lineNumber, e.getMessage()));
                }
                log.debug("Error transforming line {}: {}", lineNumber, e.getMessage());
            }
        }

        if (errorCount > 5) {
            warnings.add(String.format("... and %d more errors", errorCount - 5));
        }

        String outputCsv = String.join("\n", outputLines);
        log.info("Local transform completed: {} rows, {} errors", successCount, errorCount);

        return TransformResult.success(outputCsv, successCount, warnings);
    }

    private String[] parseCsvLine(String line, String delimiter) {
        // Handle quoted values
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        String delim = delimiter != null ? delimiter : ",";
        char delimChar = delim.charAt(0);

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimChar && !inQuotes) {
                columns.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        columns.add(current.toString().trim());

        return columns.toArray(new String[0]);
    }

    private String transformRow(String[] columns, MappingRules rules, int lineNumber) {
        Map<String, String> output = new LinkedHashMap<>();

        // Initialize with empty values
        output.put("bankTransactionId", "");
        output.put("name", "");
        output.put("description", "");
        output.put("bankCategory", "");
        output.put("amount", "");
        output.put("currency", "");
        output.put("type", "");
        output.put("operationDate", "");
        output.put("bookingDate", "");
        output.put("sourceAccountNumber", "");
        output.put("targetAccountNumber", "");
        output.put("merchant", "");
        output.put("merchantConfidence", "");
        output.put("paymentMethod", "");

        // Apply mappings
        for (ColumnMapping mapping : rules.getColumnMappings()) {
            if (mapping.getTransformationType() == TransformationType.SKIP) {
                continue;
            }

            String sourceValue = "";
            if (mapping.getSourceIndex() >= 0 && mapping.getSourceIndex() < columns.length) {
                sourceValue = columns[mapping.getSourceIndex()];
            }

            String transformedValue = applyTransformation(sourceValue, mapping, rules, columns);
            output.put(mapping.getTargetField(), transformedValue);
        }

        // Generate transaction ID if not present
        if (output.get("bankTransactionId").isBlank()) {
            output.put("bankTransactionId", generateTransactionId(output, lineNumber));
        }

        // Ensure currency has a value (fallback to rules default or PLN)
        if (output.get("currency").isBlank()) {
            String defaultCurrency = rules.getBankCountry() != null ?
                getCurrencyForCountry(rules.getBankCountry()) : "PLN";
            output.put("currency", defaultCurrency);
        }

        // Ensure name has a value (REQUIRED field - fallback to description or bankCategory)
        if (output.get("name").isBlank()) {
            // Try description first
            if (!output.get("description").isBlank()) {
                output.put("name", output.get("description"));
            }
            // Try bankCategory as last resort
            else if (!output.get("bankCategory").isBlank()) {
                output.put("name", output.get("bankCategory"));
            }
            // Generate a placeholder name from amount and date
            else {
                String placeholder = String.format("Transaction %s %s",
                    output.get("amount"), output.get("operationDate"));
                output.put("name", placeholder);
            }
        }

        // Validate required fields
        if (output.get("operationDate").isBlank() || output.get("amount").isBlank()) {
            return null; // Skip invalid rows
        }

        // Build output CSV row
        return String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
            escapeCsv(output.get("bankTransactionId")),
            escapeCsv(output.get("name")),
            escapeCsv(output.get("description")),
            escapeCsv(output.get("bankCategory")),
            output.get("amount"),
            output.get("currency"),
            output.get("type"),
            output.get("operationDate"),
            output.get("bookingDate"),
            escapeCsv(output.get("sourceAccountNumber")),
            escapeCsv(output.get("targetAccountNumber")),
            escapeCsv(output.get("merchant")),
            output.get("merchantConfidence"),
            output.get("paymentMethod")
        );
    }

    private String applyTransformation(String value, ColumnMapping mapping, MappingRules rules, String[] allColumns) {
        if (value == null) {
            value = "";
        }

        return switch (mapping.getTransformationType()) {
            case DIRECT -> value;

            case DATE_PARSE -> parseDate(value, rules.getDateFormat());

            case AMOUNT_PARSE -> parseAmount(value);

            case TYPE_DETECT -> detectType(value, allColumns, mapping.getTransformationParams());

            case CURRENCY_EXTRACT -> extractCurrency(value, mapping.getTransformationParams());

            case IBAN_NORMALIZE -> normalizeIban(value);

            case CONCAT -> concatColumns(allColumns, mapping.getTransformationParams());

            case REGEX_EXTRACT -> extractWithRegex(value, mapping.getTransformationParams());

            case VALUE_MAP -> mapValue(value, mapping.getTransformationParams());

            case ID_GENERATE -> ""; // Handled separately

            case SKIP -> "";

            case MERCHANT_EXTRACT -> extractMerchant(value, allColumns, mapping.getTransformationParams());

            case MERCHANT_CONFIDENCE -> calculateMerchantConfidence(value, allColumns, mapping.getTransformationParams());

            case PAYMENT_METHOD_NORMALIZE -> normalizePaymentMethod(value);
        };
    }

    private String parseDate(String value, String format) {
        if (value == null || value.isBlank()) {
            return "";
        }

        try {
            // Try provided format
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            LocalDate date = LocalDate.parse(value.trim(), formatter);
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            // Try common formats
            for (String fallbackFormat : List.of("dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd", "dd/MM/yyyy")) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(fallbackFormat);
                    LocalDate date = LocalDate.parse(value.trim(), formatter);
                    return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException ignored) {
                }
            }
        }
        return value;
    }

    private String parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        // Remove currency symbols and spaces
        String cleaned = value.replaceAll("[^0-9,.-]", "").trim();

        // Handle Polish format (1 234,56 -> 1234.56)
        if (cleaned.contains(",")) {
            // Check if comma is decimal separator
            int commaPos = cleaned.lastIndexOf(',');
            int dotPos = cleaned.lastIndexOf('.');

            if (commaPos > dotPos) {
                // Comma is decimal separator
                cleaned = cleaned.replace(".", "").replace(",", ".");
            } else {
                // Dot is decimal separator
                cleaned = cleaned.replace(",", "");
            }
        }

        try {
            BigDecimal amount = new BigDecimal(cleaned);
            // Always return positive value - direction is determined by type (INFLOW/OUTFLOW)
            return amount.abs().toPlainString();
        } catch (NumberFormatException e) {
            log.debug("Failed to parse amount: {}", value);
            return value;
        }
    }

    private String detectType(String value, String[] allColumns, Map<String, String> params) {
        // First priority: Check amount column sign (most reliable method)
        String amountColumn = params != null ? params.get("amountColumn") : null;
        if (amountColumn != null) {
            try {
                int amountIdx = Integer.parseInt(amountColumn);
                if (amountIdx >= 0 && amountIdx < allColumns.length) {
                    String rawAmount = allColumns[amountIdx].trim();
                    // Check for negative sign at the beginning
                    if (rawAmount.startsWith("-") || rawAmount.startsWith("−")) {
                        return "OUTFLOW";
                    }
                    // Check for negative sign anywhere (some formats put it at end)
                    if (rawAmount.contains("-") || rawAmount.contains("−")) {
                        // Make sure it's not just a thousands separator issue
                        String cleaned = rawAmount.replaceAll("[^0-9,.-−]", "");
                        if (cleaned.startsWith("-") || cleaned.startsWith("−")) {
                            return "OUTFLOW";
                        }
                    }
                    // Positive amount = INFLOW
                    return "INFLOW";
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // Second priority: If no amountColumn param, try to find amount in all columns
        // Look for any column that looks like a negative number
        if (amountColumn == null) {
            for (String col : allColumns) {
                String trimmed = col.trim();
                // Check if this looks like a monetary amount
                if (trimmed.matches("^-?[0-9\\s,.−]+$") || trimmed.matches("^-?[0-9\\s,.−]+\\s*[A-Z]{3}$")) {
                    if (trimmed.startsWith("-") || trimmed.startsWith("−")) {
                        return "OUTFLOW";
                    }
                }
            }
        }

        // Third priority: Check value text for Polish/English keywords
        if (value != null && !value.isBlank()) {
            String lower = value.toLowerCase();
            // OUTFLOW indicators
            if (lower.contains("wychodzące") || lower.contains("obciążeni") ||
                lower.contains("outgoing") || lower.contains("debit") ||
                lower.contains("wydatek") || lower.contains("opłat") ||
                lower.contains("wypłata") || lower.contains("płatność") ||
                lower.contains("przelew wychodzący")) {
                return "OUTFLOW";
            }
            // INFLOW indicators
            if (lower.contains("przychodzące") || lower.contains("uznani") ||
                lower.contains("incoming") || lower.contains("credit") ||
                lower.contains("przychod") || lower.contains("wpływ") ||
                lower.contains("wpłata") || lower.contains("przelew przychodzący")) {
                return "INFLOW";
            }
        }

        return "OUTFLOW"; // Default to outflow for safety (most bank transactions are expenses)
    }

    private String extractCurrency(String value, Map<String, String> params) {
        if (value == null || value.isBlank()) {
            return params != null ? params.getOrDefault("default", "PLN") : "PLN";
        }

        // Extract currency code from value
        Matcher matcher = Pattern.compile("([A-Z]{3})").matcher(value.toUpperCase());
        if (matcher.find()) {
            return matcher.group(1);
        }

        return params != null ? params.getOrDefault("default", "PLN") : "PLN";
    }

    private String normalizeIban(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        // Remove spaces and special characters
        String cleaned = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase();

        // Add PL prefix if it's a 26-digit Polish account
        if (cleaned.length() == 26 && cleaned.matches("\\d+")) {
            cleaned = "PL" + cleaned;
        }

        return cleaned;
    }

    private String concatColumns(String[] columns, Map<String, String> params) {
        if (params == null || !params.containsKey("indices")) {
            return "";
        }

        String[] indices = params.get("indices").split(",");
        String separator = params.getOrDefault("separator", " ");

        StringBuilder result = new StringBuilder();
        for (String idxStr : indices) {
            try {
                int idx = Integer.parseInt(idxStr.trim());
                if (idx >= 0 && idx < columns.length) {
                    String val = columns[idx].trim();
                    if (!val.isEmpty()) {
                        if (result.length() > 0) {
                            result.append(separator);
                        }
                        result.append(val);
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return result.toString();
    }

    private String extractWithRegex(String value, Map<String, String> params) {
        if (value == null || params == null || !params.containsKey("pattern")) {
            return value;
        }

        try {
            Pattern pattern = Pattern.compile(params.get("pattern"));
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) {
                int group = Integer.parseInt(params.getOrDefault("group", "0"));
                return matcher.group(group);
            }
        } catch (Exception e) {
            log.debug("Regex extraction failed: {}", e.getMessage());
        }

        return value;
    }

    private String mapValue(String value, Map<String, String> params) {
        if (value == null || params == null) {
            return value;
        }

        return params.getOrDefault(value.trim(), value);
    }

    private String generateTransactionId(Map<String, String> row, int lineNumber) {
        // Generate deterministic ID from row content
        String content = row.get("operationDate") + row.get("amount") + row.get("name") + lineNumber;
        return "TXN-" + Math.abs(content.hashCode());
    }

    /**
     * Get default currency for a country code.
     */
    private String getCurrencyForCountry(String countryCode) {
        if (countryCode == null) return "PLN";
        return switch (countryCode.toUpperCase()) {
            case "PL" -> "PLN";
            case "DE", "FR", "ES", "IT", "NL", "AT", "BE", "FI", "IE", "PT", "GR" -> "EUR";
            case "GB", "UK" -> "GBP";
            case "US" -> "USD";
            case "CH" -> "CHF";
            case "CZ" -> "CZK";
            case "SE" -> "SEK";
            case "NO" -> "NOK";
            case "DK" -> "DKK";
            default -> "PLN"; // Default for Polish banks
        };
    }

    /**
     * Extract merchant name from transaction description.
     * Used when the "name" field contains a bank intermediary (e.g., "BANK PEKAO S.A.")
     * but the real merchant (BADOO, NETFLIX, OPENAI) is hidden in description.
     *
     * Common patterns in Polish bank descriptions:
     * - "Nadawca: BADOO help@badoo.com" → BADOO
     * - "ROZLICZENIE TRANSAKCJI ZAGRANICZNYCH Nadawca: Netflix" → NETFLIX
     * - "Odbiorca: ANTHROPIC" → ANTHROPIC
     */
    private String extractMerchant(String description, String[] allColumns, Map<String, String> params) {
        if (description == null || description.isBlank()) {
            return "";
        }

        // Check if name column contains bank intermediary (indicating merchant should be extracted)
        String nameColumn = params != null ? params.get("nameColumn") : null;
        if (nameColumn != null) {
            try {
                int nameIdx = Integer.parseInt(nameColumn);
                if (nameIdx >= 0 && nameIdx < allColumns.length) {
                    String name = allColumns[nameIdx].toUpperCase();
                    // Only extract merchant if name looks like a bank intermediary
                    if (!isBankIntermediary(name)) {
                        return ""; // Name is already the real merchant
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // Try extraction patterns
        String descUpper = description.toUpperCase();

        // Pattern 1: "Nadawca: XXX" or "Odbiorca: XXX"
        Pattern senderRecipient = Pattern.compile("(?:Nadawca|Odbiorca):\\s*([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
        Matcher m1 = senderRecipient.matcher(description);
        if (m1.find()) {
            String merchant = m1.group(1).toUpperCase();
            if (isValidMerchantName(merchant)) {
                return merchant;
            }
        }

        // Pattern 2: Well-known services in description
        String[] knownMerchants = {"NETFLIX", "SPOTIFY", "OPENAI", "ANTHROPIC", "CLAUDE", "BADOO",
            "GOOGLE", "APPLE", "AMAZON", "MICROSOFT", "FACEBOOK", "META", "PAYPAL", "UBER", "BOLT"};
        for (String known : knownMerchants) {
            if (descUpper.contains(known)) {
                return known;
            }
        }

        // Pattern 3: First word after "Tytuł:" or "Title:"
        Pattern titlePattern = Pattern.compile("(?:Tytu[łl]|Title):\\s*([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
        Matcher m3 = titlePattern.matcher(description);
        if (m3.find()) {
            String merchant = m3.group(1).toUpperCase();
            if (isValidMerchantName(merchant)) {
                return merchant;
            }
        }

        return "";
    }

    /**
     * Check if name looks like a bank intermediary rather than actual merchant.
     */
    private boolean isBankIntermediary(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase();
        return upper.contains("BANK") ||
               upper.contains("PEKAO") ||
               upper.contains("PKO") ||
               upper.contains("MBANK") ||
               upper.contains("ING ") ||
               upper.contains("SANTANDER") ||
               upper.contains("BNP") ||
               upper.contains("PARIBAS") ||
               upper.contains("NEST ") ||
               upper.contains("ALIOR") ||
               upper.contains("MILLENNIUM") ||
               upper.contains("GETIN") ||
               upper.contains("BOS ") ||
               upper.contains("CREDIT ") ||
               upper.contains("ROZLICZENIE");
    }

    /**
     * Check if extracted name is a valid merchant (not a generic word).
     */
    private boolean isValidMerchantName(String name) {
        if (name == null || name.length() < 3) return false;
        // Exclude generic Polish/English words
        String upper = name.toUpperCase();
        return !upper.equals("PAN") && !upper.equals("PANI") &&
               !upper.equals("MR") && !upper.equals("MRS") &&
               !upper.equals("THE") && !upper.equals("AND") &&
               !upper.equals("DLA") && !upper.equals("OD") &&
               !upper.equals("DO") && !upper.equals("NA") &&
               !upper.equals("ZA") && !upper.equals("PRZELEW");
    }

    /**
     * Normalize payment method from various bank formats to standard enum values.
     * Converts Polish/other language payment types to English enum: CARD, TRANSFER, BLIK, etc.
     */
    private String normalizePaymentMethod(String value) {
        if (value == null || value.isBlank()) {
            return "OTHER";
        }

        String upper = value.toUpperCase().trim();

        // CARD payments
        if (upper.contains("KART") || upper.contains("CARD") ||
            upper.contains("VISA") || upper.contains("MASTERCARD") ||
            upper.contains("PŁATNOŚĆ KARTĄ") || upper.contains("TRANSAKCJA KARTĄ")) {
            return "CARD";
        }

        // BLIK payments
        if (upper.contains("BLIK")) {
            return "BLIK";
        }

        // TRANSFER payments
        if (upper.contains("PRZELEW") || upper.contains("TRANSFER") ||
            upper.contains("WIRE") || upper.contains("ELIXIR") ||
            upper.contains("SORBNET") || upper.contains("SWIFT") ||
            upper.contains("EXPRESS ELIXIR")) {
            return "TRANSFER";
        }

        // DIRECT DEBIT
        if (upper.contains("POLECENIE ZAPŁATY") || upper.contains("DIRECT DEBIT") ||
            upper.contains("OBCIĄŻENIE") || upper.contains("INKASO")) {
            return "DIRECT_DEBIT";
        }

        // STANDING ORDER
        if (upper.contains("ZLECENIE STAŁE") || upper.contains("STANDING ORDER") ||
            upper.contains("RECURRING") || upper.contains("AUTOMATYCZNY PRZELEW")) {
            return "STANDING_ORDER";
        }

        // CASH operations
        if (upper.contains("GOTÓWKA") || upper.contains("CASH") ||
            upper.contains("WYPŁATA") || upper.contains("WPŁATA") ||
            upper.contains("WITHDRAWAL") || upper.contains("DEPOSIT") ||
            upper.contains("BANKOMAT") || upper.contains("ATM")) {
            return "CASH";
        }

        return "OTHER";
    }

    /**
     * Calculate confidence score for merchant extraction.
     * Returns value between 0.0 and 1.0.
     */
    private String calculateMerchantConfidence(String description, String[] allColumns, Map<String, String> params) {
        if (description == null || description.isBlank()) {
            return "";
        }

        String descUpper = description.toUpperCase();

        // High confidence: Known merchant names
        String[] knownMerchants = {"NETFLIX", "SPOTIFY", "OPENAI", "ANTHROPIC", "CLAUDE", "BADOO",
            "GOOGLE", "APPLE", "AMAZON", "MICROSOFT", "FACEBOOK", "META", "PAYPAL", "UBER", "BOLT"};
        for (String known : knownMerchants) {
            if (descUpper.contains(known)) {
                return "0.95"; // Very high confidence for known merchants
            }
        }

        // Medium-high confidence: Clear sender/recipient pattern
        if (description.matches("(?i).*(?:Nadawca|Odbiorca):\\s*[A-Za-z0-9]+.*")) {
            return "0.85";
        }

        // Medium confidence: Title pattern
        if (description.matches("(?i).*(?:Tytu[łl]|Title):\\s*[A-Za-z0-9]+.*")) {
            return "0.70";
        }

        // Low confidence: Generic description
        return "";
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }

        // Remove or replace problematic characters
        value = value.replace("|", " ").replace("\r", " ").replace("\n", " ");

        // Quote if contains comma, quote, or special chars
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }

    /**
     * Result of local transformation.
     */
    public record TransformResult(
        boolean success,
        String csvContent,
        int rowCount,
        List<String> warnings,
        String errorMessage
    ) {
        public static TransformResult success(String csv, int rows, List<String> warnings) {
            return new TransformResult(true, csv, rows, warnings, null);
        }

        public static TransformResult failure(String error) {
            return new TransformResult(false, null, 0, List.of(), error);
        }
    }
}
