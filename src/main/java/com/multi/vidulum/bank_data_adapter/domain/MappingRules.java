package com.multi.vidulum.bank_data_adapter.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Represents learned mapping rules for transforming bank CSV to BankCsvRow format.
 * Cached per bank to avoid repeated AI calls for the same bank format.
 *
 * The bankIdentifier is a hash of the CSV structure (header row) that uniquely
 * identifies a bank's CSV format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_mapping_rules")
public class MappingRules {

    @Id
    private String id;

    /**
     * Unique identifier for the bank's CSV format.
     * Computed as hash of: header row + metadata structure.
     */
    @Indexed(unique = true)
    private String bankIdentifier;

    /**
     * Detected bank name (e.g., "Nest Bank", "mBank", "PKO BP")
     */
    private String bankName;

    /**
     * Detected bank country (ISO code, e.g., "PL")
     */
    private String bankCountry;

    /**
     * Language of the CSV (e.g., "pl", "en")
     */
    private String language;

    /**
     * Column mappings from source to BankCsvRow fields.
     */
    private List<ColumnMapping> columnMappings;

    /**
     * Date format used in the CSV (e.g., "dd-MM-yyyy", "yyyy-MM-dd")
     */
    private String dateFormat;

    /**
     * CSV delimiter (e.g., ",", ";", "\t")
     */
    private String delimiter;

    /**
     * Character encoding (e.g., "UTF-8", "CP1250")
     */
    private String encoding;

    /**
     * Index of the header row (0-based, may be > 0 if there's metadata)
     */
    private int headerRowIndex;

    /**
     * Number of metadata rows before header (bank name, account info, etc.)
     */
    private int metadataRows;

    /**
     * Original header row for reference
     */
    private String originalHeader;

    /**
     * Sample transformation for validation
     */
    private String sampleInputRow;
    private String sampleOutputRow;

    /**
     * AI model that generated these rules
     */
    private String generatedByModel;

    /**
     * Version of the prompt that generated these rules
     */
    private String promptVersion;

    /**
     * Usage statistics
     */
    private int usageCount;
    private Date lastUsedAt;

    /**
     * Creation metadata
     */
    private Date createdAt;
    private String createdByUserId;

    /**
     * Confidence score from AI (0.0 - 1.0)
     */
    private double confidenceScore;

    /**
     * Any warnings or notes from AI
     */
    private List<String> warnings;

    // ============ Helper methods ============

    public void incrementUsage() {
        this.usageCount++;
        this.lastUsedAt = new Date();
    }

    public ZonedDateTime getCreatedAtZoned() {
        return createdAt != null ?
            ZonedDateTime.ofInstant(createdAt.toInstant(), java.time.ZoneId.systemDefault()) : null;
    }

    public ZonedDateTime getLastUsedAtZoned() {
        return lastUsedAt != null ?
            ZonedDateTime.ofInstant(lastUsedAt.toInstant(), java.time.ZoneId.systemDefault()) : null;
    }

    /**
     * Represents a single column mapping from source to target.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnMapping {

        /**
         * Source column name/index from bank CSV
         */
        private String sourceColumn;

        /**
         * Source column index (0-based)
         */
        private int sourceIndex;

        /**
         * Target BankCsvRow field name
         */
        private String targetField;

        /**
         * Transformation type to apply
         */
        private TransformationType transformationType;

        /**
         * Additional parameters for transformation
         */
        private Map<String, String> transformationParams;

        /**
         * Whether this mapping is required (vs optional)
         */
        private boolean required;
    }

    /**
     * Types of transformations that can be applied to column values.
     */
    public enum TransformationType {
        /**
         * Direct copy without transformation
         */
        DIRECT,

        /**
         * Parse date with specific format
         */
        DATE_PARSE,

        /**
         * Parse amount (handle Polish format: 1 234,56)
         */
        AMOUNT_PARSE,

        /**
         * Determine transaction type from amount sign or text
         */
        TYPE_DETECT,

        /**
         * Extract currency from amount or separate column
         */
        CURRENCY_EXTRACT,

        /**
         * Normalize IBAN/account number
         */
        IBAN_NORMALIZE,

        /**
         * Combine multiple columns
         */
        CONCAT,

        /**
         * Extract part of value using regex
         */
        REGEX_EXTRACT,

        /**
         * Map value using lookup table
         */
        VALUE_MAP,

        /**
         * Generate unique ID if not present
         */
        ID_GENERATE,

        /**
         * Skip this column (not mapped to output)
         */
        SKIP
    }
}
