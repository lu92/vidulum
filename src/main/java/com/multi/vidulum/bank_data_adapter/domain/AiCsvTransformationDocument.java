package com.multi.vidulum.bank_data_adapter.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;

/**
 * MongoDB document for storing AI CSV transformation audit records.
 * Each transformation is saved with full metadata for audit and debugging.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_csv_transformations")
public class AiCsvTransformationDocument {

    // ========== IDENTIFIERS ==========
    @Id
    private String id;                              // UUID

    @Indexed
    private String userId;                          // Who requested the transformation

    private String cashFlowId;                      // Optional - set when imported

    // ========== ORIGINAL FILE ==========
    private String originalFileName;                // "lista_operacji_20260111.csv"
    private int originalFileSizeBytes;              // Size in bytes

    @Indexed
    private String originalFileHash;                // SHA-256 for deduplication

    private String originalCsvContent;              // Full content of original CSV
    private String detectedEncoding;                // "UTF-8", "CP1250"
    private String detectedLanguage;                // "pl", "en", "de" (detected by AI)

    // ========== BANK DETECTION ==========
    private String bankHint;                        // User hint (optional)

    @Indexed
    private String detectedBank;                    // Detected by AI: "Nest", "mBank", "ING", "unknown"

    private String detectedCountry;                 // "PL", "DE", "UK"

    // ========== TRANSFORMATION RESULT ==========
    private boolean success;                        // Whether transformation succeeded
    private String transformedCsvContent;           // Result: BankCsvRow CSV (null if error)
    private int inputRowCount;                      // Input row count
    private int outputRowCount;                     // Output row count
    private int skippedRowCount;                    // Skipped rows
    private List<String> warnings;                  // Warnings from AI

    // ========== ERRORS ==========
    private String errorCode;                       // Error code (null if success)
    private String errorMessage;                    // Error message

    // ========== AI METRICS ==========
    private String aiModel;                         // "claude-3-5-haiku-20241022"
    private int inputTokens;                        // Input tokens
    private int outputTokens;                       // Output tokens
    private BigDecimal estimatedCostUsd;            // Estimated cost in USD
    private long processingTimeMs;                  // Processing time
    private int retryCount;                         // Retry count (0 = success on first try)

    // ========== AUDIT ==========
    @Indexed
    private Date createdAt;                         // When created (stored as Date in MongoDB)

    private String createdBy;                       // User ID

    // ========== IMPORT STATUS ==========
    @Indexed
    private ImportStatus importStatus;              // PENDING, IMPORTED, SKIPPED, FAILED

    private String stagingSessionId;                // Staging session ID (if imported)
    private Date importedAt;                        // When imported (stored as Date in MongoDB)

    // ========== CACHE TRACKING ==========
    private boolean fromCache;                      // Whether this transformation used cached mapping rules
    @Indexed
    private String bankIdentifier;                  // Bank format identifier for cache lookup

    // ========== DETECTION RESULT ==========
    private DetectionResult detectionResult;        // CANONICAL, CACHED, AI_TRANSFORMED

    // ========== DATE RANGE STATISTICS ==========
    private LocalDate minTransactionDate;           // Earliest transaction date in CSV
    private LocalDate maxTransactionDate;           // Latest transaction date in CSV
    private String suggestedStartPeriod;            // YearMonth string, e.g., "2023-06"
    private int monthsOfData;                       // Number of distinct months covered
    private List<String> monthsCovered;             // List of "YYYY-MM" strings in order

    // ========== ENRICHMENT (ETAP 2) ==========
    private boolean enrichmentApplied;              // Whether enrichment was applied
    private Date enrichedAt;                        // When enrichment was applied
    private long enrichmentTimeMs;                  // Enrichment processing time
    private int enrichmentAiCalls;                  // Number of AI calls for enrichment (batches)
    private int merchantsExtracted;                 // Number of merchants extracted
    private int bankCategoriesInferred;             // Number of bank categories inferred (were empty)
    private int bankCategoriesKept;                 // Number of bank categories kept (were filled)
    private int enrichmentFallbackCount;            // Number of transactions using fallback
    private String enrichmentNotes;                 // Processing notes from enrichment

    // ========== HELPER METHODS FOR ZONEDDATETIME CONVERSION ==========

    public ZonedDateTime getCreatedAtZoned() {
        return createdAt != null ? ZonedDateTime.ofInstant(createdAt.toInstant(), ZoneOffset.UTC) : null;
    }

    public void setCreatedAtFromZoned(ZonedDateTime zdt) {
        this.createdAt = zdt != null ? Date.from(zdt.toInstant()) : null;
    }

    public ZonedDateTime getImportedAtZoned() {
        return importedAt != null ? ZonedDateTime.ofInstant(importedAt.toInstant(), ZoneOffset.UTC) : null;
    }

    public void setImportedAtFromZoned(ZonedDateTime zdt) {
        this.importedAt = zdt != null ? Date.from(zdt.toInstant()) : null;
    }

    public ZonedDateTime getEnrichedAtZoned() {
        return enrichedAt != null ? ZonedDateTime.ofInstant(enrichedAt.toInstant(), ZoneOffset.UTC) : null;
    }

    public void setEnrichedAtFromZoned(ZonedDateTime zdt) {
        this.enrichedAt = zdt != null ? Date.from(zdt.toInstant()) : null;
    }

    public static Date toDate(ZonedDateTime zdt) {
        return zdt != null ? Date.from(zdt.toInstant()) : null;
    }
}
