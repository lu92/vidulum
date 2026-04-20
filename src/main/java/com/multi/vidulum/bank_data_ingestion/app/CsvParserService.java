package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_adapter.domain.TransactionClassification;
import com.multi.vidulum.bank_data_ingestion.domain.BankCsvRow;
import com.multi.vidulum.bank_data_ingestion.domain.PaymentMethod;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service for parsing CSV files with bank transactions.
 * Expects normalized BankCsvRow format.
 */
@Slf4j
@Service
public class CsvParserService {

    private static final String[] HEADERS = {
            "bankTransactionId",
            "name",
            "description",
            "bankCategory",
            "amount",
            "currency",
            "type",
            "operationDate",
            "bookingDate",
            "sourceAccountNumber",
            "targetAccountNumber",
            "merchant",
            "merchantConfidence",
            "paymentMethod",
            "classification",
            "classificationReason",
            "location"
    };

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,           // 2021-08-15
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),  // 15.08.2021
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),  // 15/08/2021
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),  // 08/15/2021
            DateTimeFormatter.ofPattern("yyyy/MM/dd")   // 2021/08/15
    );

    // IBAN validation pattern: 2 letter country code + 2 check digits + up to 30 alphanumeric
    private static final Pattern IBAN_PATTERN = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{1,30}$");

    // IBAN lengths per country (without spaces)
    private static final Map<String, Integer> IBAN_LENGTHS = Map.ofEntries(
            Map.entry("PL", 28),
            Map.entry("DE", 22),
            Map.entry("GB", 22),
            Map.entry("FR", 27),
            Map.entry("ES", 24),
            Map.entry("IT", 27),
            Map.entry("NL", 18),
            Map.entry("AT", 20),
            Map.entry("BE", 16),
            Map.entry("CH", 21),
            Map.entry("CZ", 24),
            Map.entry("SE", 24),
            Map.entry("NO", 15),
            Map.entry("DK", 18)
    );

    // Currency to default country mapping (for IBAN prefix inference)
    private static final Map<String, String> CURRENCY_TO_COUNTRY = Map.of(
            "PLN", "PL",
            "EUR", "DE",  // Default EUR to Germany
            "GBP", "GB",
            "CHF", "CH",
            "CZK", "CZ",
            "SEK", "SE",
            "NOK", "NO",
            "DKK", "DK"
    );

    /**
     * Parse CSV file into list of BankCsvRow.
     *
     * @param file CSV file with BankCsvRow format
     * @return list of parsed rows
     * @throws CsvParseException if parsing fails
     */
    public CsvParseResult parse(MultipartFile file) {
        List<BankCsvRow> rows = new ArrayList<>();
        List<CsvParseError> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader(HEADERS)
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .build();

            CSVParser parser = format.parse(reader);

            int rowNumber = 1; // 1-based, header is row 0
            for (CSVRecord record : parser) {
                rowNumber++;
                try {
                    BankCsvRow row = parseRow(record);
                    validateRow(row, rowNumber);
                    rows.add(row);
                } catch (Exception e) {
                    errors.add(new CsvParseError(rowNumber, e.getMessage()));
                    log.warn("Error parsing row {}: {}", rowNumber, e.getMessage());
                }
            }

        } catch (IOException e) {
            throw new CsvParseException("Failed to read CSV file: " + e.getMessage(), e);
        }

        log.info("Parsed CSV file: {} rows successful, {} errors", rows.size(), errors.size());
        return new CsvParseResult(rows, errors);
    }

    private BankCsvRow parseRow(CSVRecord record) {
        String currency = getRequiredString(record, "currency");
        return new BankCsvRow(
                getOptionalString(record, "bankTransactionId"),
                getRequiredString(record, "name"),
                getOptionalString(record, "description"),
                getOptionalString(record, "bankCategory"),
                getRequiredBigDecimal(record, "amount"),
                currency,
                getRequiredType(record, "type"),
                getRequiredDate(record, "operationDate"),
                getOptionalDate(record, "bookingDate"),
                normalizeIban(getOptionalString(record, "sourceAccountNumber"), currency),
                normalizeIban(getOptionalString(record, "targetAccountNumber"), currency),
                getOptionalString(record, "merchant"),
                getOptionalDouble(record, "merchantConfidence"),
                getOptionalPaymentMethod(record, "paymentMethod"),
                getOptionalClassification(record, "classification"),
                getOptionalString(record, "classificationReason"),
                getOptionalString(record, "location")
        );
    }

    private TransactionClassification getOptionalClassification(CSVRecord record, String header) {
        String value = getOptionalString(record, header);
        return TransactionClassification.fromString(value);
    }

    private void validateRow(BankCsvRow row, int rowNumber) {
        List<String> validationErrors = new ArrayList<>();

        if (row.name() == null || row.name().isBlank()) {
            validationErrors.add("name is required");
        }
        if (row.amount() == null) {
            validationErrors.add("amount is required");
        } else if (row.amount().compareTo(BigDecimal.ZERO) <= 0) {
            validationErrors.add("amount must be positive");
        }
        if (row.currency() == null || row.currency().isBlank()) {
            validationErrors.add("currency is required");
        }
        if (row.type() == null) {
            validationErrors.add("type is required");
        }
        if (row.operationDate() == null) {
            validationErrors.add("operationDate is required");
        }

        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException("Validation failed: " + String.join(", ", validationErrors));
        }
    }

    private String getRequiredString(CSVRecord record, String column) {
        String value = record.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(column + " is required");
        }
        return value.trim();
    }

    private String getOptionalString(CSVRecord record, String column) {
        try {
            String value = record.get(column);
            return (value == null || value.isBlank()) ? null : value.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private BigDecimal getRequiredBigDecimal(CSVRecord record, String column) {
        String value = record.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(column + " is required");
        }
        try {
            // Handle both comma and dot as decimal separator
            String normalized = value.trim().replace(",", ".");
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a valid number: " + value);
        }
    }

    private Type getRequiredType(CSVRecord record, String column) {
        String value = record.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(column + " is required");
        }
        try {
            return Type.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(column + " must be INFLOW or OUTFLOW: " + value);
        }
    }

    private LocalDate getRequiredDate(CSVRecord record, String column) {
        String value = record.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(column + " is required");
        }
        return parseDate(value.trim(), column);
    }

    private LocalDate getOptionalDate(CSVRecord record, String column) {
        try {
            String value = record.get(column);
            if (value == null || value.isBlank()) {
                return null;
            }
            return parseDate(value.trim(), column);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Double getOptionalDouble(CSVRecord record, String column) {
        try {
            String value = record.get(column);
            if (value == null || value.isBlank()) {
                return null;
            }
            // Handle both comma and dot as decimal separator
            String normalized = value.trim().replace(",", ".");
            return Double.parseDouble(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    private PaymentMethod getOptionalPaymentMethod(CSVRecord record, String column) {
        try {
            String value = record.get(column);
            if (value == null || value.isBlank()) {
                return null;
            }
            return PaymentMethod.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String value, String column) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // Try next formatter
            }
        }
        throw new IllegalArgumentException(column + " has invalid date format: " + value);
    }

    /**
     * Normalizes account number to full IBAN format with country prefix.
     * - Removes spaces and dashes
     * - Adds country prefix if missing (based on currency)
     * - Returns null if input is null or empty
     *
     * @param accountNumber raw account number (may or may not have country prefix)
     * @param currency      currency code to infer country if prefix missing
     * @return normalized IBAN or null
     */
    private String normalizeIban(String accountNumber, String currency) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return null;
        }

        // Remove spaces and dashes
        String cleaned = accountNumber.replaceAll("[\\s\\-]", "").toUpperCase();

        // Check if already has country prefix (2 letters at start)
        if (cleaned.length() >= 2 && Character.isLetter(cleaned.charAt(0)) && Character.isLetter(cleaned.charAt(1))) {
            // Already has prefix, validate format
            if (isValidIbanFormat(cleaned)) {
                return cleaned;
            }
            // Has letters but invalid format - return as-is with warning
            log.warn("Account number has country prefix but invalid IBAN format: {}", accountNumber);
            return cleaned;
        }

        // No country prefix - try to infer from currency
        String countryCode = CURRENCY_TO_COUNTRY.get(currency);
        if (countryCode != null) {
            Integer expectedLength = IBAN_LENGTHS.get(countryCode);
            // Check if adding prefix would give expected length
            if (expectedLength != null && cleaned.length() == expectedLength - 2) {
                String withPrefix = countryCode + cleaned;
                log.debug("Normalized account {} to IBAN {} (inferred from currency {})",
                        accountNumber, withPrefix, currency);
                return withPrefix;
            }
        }

        // Cannot normalize - return as-is
        log.debug("Cannot normalize account number to IBAN: {} (currency: {})", accountNumber, currency);
        return cleaned;
    }

    /**
     * Validates IBAN format (basic structure check, not checksum).
     */
    private boolean isValidIbanFormat(String iban) {
        if (!IBAN_PATTERN.matcher(iban).matches()) {
            return false;
        }

        // Check length for known countries
        String countryCode = iban.substring(0, 2);
        Integer expectedLength = IBAN_LENGTHS.get(countryCode);
        if (expectedLength != null && iban.length() != expectedLength) {
            log.warn("IBAN {} has invalid length for country {} (expected {}, got {})",
                    iban, countryCode, expectedLength, iban.length());
            return false;
        }

        return true;
    }

    /**
     * Result of CSV parsing containing successful rows and errors.
     */
    public record CsvParseResult(
            List<BankCsvRow> rows,
            List<CsvParseError> errors
    ) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public int totalRows() {
            return rows.size() + errors.size();
        }
    }

    /**
     * Error encountered while parsing a specific row.
     */
    public record CsvParseError(
            int rowNumber,
            String message
    ) {}

    /**
     * Exception thrown when CSV parsing fails completely.
     */
    public static class CsvParseException extends RuntimeException {
        public CsvParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
