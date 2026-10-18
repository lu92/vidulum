package com.multi.vidulum.bank_data_ingestion.infrastructure.entity;

import com.multi.vidulum.bank_data_ingestion.domain.AiCategorizationStatus;
import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB entity representing a staging session.
 * A staging session groups transactions from the same import operation and stores
 * metadata about the import source, language detection, and AI categorization status.
 *
 * <p>This entity serves as the single source of truth for staging session state,
 * eliminating the need to compute session status from transactions each time.</p>
 */
@Builder
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Document("staging_sessions")
@CompoundIndex(name = "cashflow_status_idx", def = "{'cashFlowId': 1, 'status': 1}")
public class StagingSessionEntity {

    @Id
    private String sessionId;

    @Indexed
    private String cashFlowId;

    /**
     * ID of the AI transformation that created this session, if any.
     * Null when session was created from direct CSV upload.
     */
    private String transformationId;

    // ============ Source Metadata ============

    /**
     * Language detected by AI during transformation (e.g., "pl", "en", "de").
     * Used to generate category names in the correct language.
     */
    private String detectedLanguage;

    /**
     * Bank detected by AI during transformation (e.g., "Nest Bank", "PKO BP").
     */
    private String detectedBank;

    /**
     * Country detected from bank or IBAN (e.g., "PL", "DE").
     */
    private String detectedCountry;

    /**
     * Original uploaded file name.
     */
    private String originalFileName;

    // ============ Audit ============

    @Indexed
    private String createdByUserId;

    private Instant createdAt;

    private Instant lastModifiedAt;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    // ============ Status ============

    @Indexed
    private StagingSessionStatus status;

    private AiCategorizationStatus aiCategorizationStatus;

    // ============ Summary (denormalized for quick access) ============

    private int totalTransactions;
    private int validTransactions;
    private int invalidTransactions;
    private int duplicateTransactions;
    private int unmappedTransactions;

    // ============ AI Categorization Results ============

    private Instant aiCategorizationStartedAt;
    private Instant aiCategorizationCompletedAt;
    private Integer aiTokensUsed;
    private String aiEstimatedCost;

    // ============ Import Job Results ============

    private String importJobId;
    private Instant importStartedAt;
    private Instant importCompletedAt;
    private Integer importedTransactionsCount;

    // ============ Lifecycle Methods ============

    /**
     * Creates a new staging session entity with initial state.
     *
     * @param now The current instant from the injected Clock (for testability)
     */
    public static StagingSessionEntity create(
            String sessionId,
            String cashFlowId,
            String transformationId,
            String detectedLanguage,
            String detectedBank,
            String detectedCountry,
            String originalFileName,
            String createdByUserId,
            Instant expiresAt,
            Instant now
    ) {
        return StagingSessionEntity.builder()
                .sessionId(sessionId)
                .cashFlowId(cashFlowId)
                .transformationId(transformationId)
                .detectedLanguage(detectedLanguage)
                .detectedBank(detectedBank)
                .detectedCountry(detectedCountry)
                .originalFileName(originalFileName)
                .createdByUserId(createdByUserId)
                .createdAt(now)
                .lastModifiedAt(now)
                .expiresAt(expiresAt)
                .status(StagingSessionStatus.PENDING)
                .aiCategorizationStatus(AiCategorizationStatus.NOT_STARTED)
                .totalTransactions(0)
                .validTransactions(0)
                .invalidTransactions(0)
                .duplicateTransactions(0)
                .unmappedTransactions(0)
                .build();
    }

    /**
     * Updates transaction summary counts and recalculates status.
     */
    public void updateSummary(
            int totalTransactions,
            int validTransactions,
            int invalidTransactions,
            int duplicateTransactions,
            int unmappedTransactions
    ) {
        this.totalTransactions = totalTransactions;
        this.validTransactions = validTransactions;
        this.invalidTransactions = invalidTransactions;
        this.duplicateTransactions = duplicateTransactions;
        this.unmappedTransactions = unmappedTransactions;
        this.lastModifiedAt = Instant.now();

        // Recalculate status based on summary
        recalculateStatus();
    }

    /**
     * Recalculates session status based on current summary and AI categorization state.
     */
    public void recalculateStatus() {
        // Don't change status if already in terminal states
        if (status == StagingSessionStatus.IMPORTING ||
            status == StagingSessionStatus.COMPLETED ||
            status == StagingSessionStatus.EXPIRED) {
            return;
        }

        if (invalidTransactions > 0) {
            this.status = StagingSessionStatus.HAS_VALIDATION_ERRORS;
        } else if (unmappedTransactions > 0) {
            // Check if AI suggestions are ready
            if (aiCategorizationStatus == AiCategorizationStatus.COMPLETED) {
                this.status = StagingSessionStatus.AI_SUGGESTIONS_READY;
            } else {
                this.status = StagingSessionStatus.HAS_UNMAPPED_CATEGORIES;
            }
        } else {
            this.status = StagingSessionStatus.READY_FOR_IMPORT;
        }
    }

    /**
     * Marks AI categorization as started.
     */
    public void startAiCategorization() {
        this.aiCategorizationStatus = AiCategorizationStatus.IN_PROGRESS;
        this.aiCategorizationStartedAt = Instant.now();
        this.lastModifiedAt = Instant.now();
    }

    /**
     * Marks AI categorization as completed.
     */
    public void completeAiCategorization(int tokensUsed, String estimatedCost) {
        this.aiCategorizationStatus = AiCategorizationStatus.COMPLETED;
        this.aiCategorizationCompletedAt = Instant.now();
        this.aiTokensUsed = tokensUsed;
        this.aiEstimatedCost = estimatedCost;
        this.lastModifiedAt = Instant.now();

        // Update session status to AI_SUGGESTIONS_READY if there are still unmapped
        if (unmappedTransactions > 0) {
            this.status = StagingSessionStatus.AI_SUGGESTIONS_READY;
        }
    }

    /**
     * Marks AI categorization as failed.
     */
    public void failAiCategorization() {
        this.aiCategorizationStatus = AiCategorizationStatus.FAILED;
        this.lastModifiedAt = Instant.now();
    }

    /**
     * Marks AI categorization as skipped (user chose manual mapping).
     */
    public void skipAiCategorization() {
        this.aiCategorizationStatus = AiCategorizationStatus.SKIPPED;
        this.lastModifiedAt = Instant.now();
    }

    /**
     * Marks session as importing.
     */
    public void startImport(String importJobId) {
        this.status = StagingSessionStatus.IMPORTING;
        this.importJobId = importJobId;
        this.importStartedAt = Instant.now();
        this.lastModifiedAt = Instant.now();
    }

    /**
     * Marks session as completed after successful import.
     */
    public void completeImport(int importedCount) {
        this.status = StagingSessionStatus.COMPLETED;
        this.importedTransactionsCount = importedCount;
        this.importCompletedAt = Instant.now();
        this.lastModifiedAt = Instant.now();
    }

    /**
     * Marks import as failed, returning to READY_FOR_IMPORT state.
     */
    public void failImport() {
        // Go back to READY_FOR_IMPORT so user can retry
        this.status = StagingSessionStatus.READY_FOR_IMPORT;
        this.importJobId = null;  // Clear failed job reference
        this.importStartedAt = null;
        this.lastModifiedAt = Instant.now();
    }
}
