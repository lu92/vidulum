package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.domain.BankCsvRow;
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
            "targetAccountNumber"
    };

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,           // 2021-08-15
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),  // 15.08.2021
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),  // 15/08/2021
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),  // 08/15/2021
            DateTimeFormatter.ofPattern("yyyy/MM/dd")   // 2021/08/15
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
        return new BankCsvRow(
                getOptionalString(record, "bankTransactionId"),
                getRequiredString(record, "name"),
                getOptionalString(record, "description"),
                getOptionalString(record, "bankCategory"),
                getRequiredBigDecimal(record, "amount"),
                getRequiredString(record, "currency"),
                getRequiredType(record, "type"),
                getRequiredDate(record, "operationDate"),
                getOptionalDate(record, "bookingDate"),
                getOptionalString(record, "sourceAccountNumber"),
                getOptionalString(record, "targetAccountNumber")
        );
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
