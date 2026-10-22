package com.multi.vidulum.bank_data_ingestion.staging.infrastructure.entity;

import com.multi.vidulum.bank_data_ingestion.staging.domain.ClassificationStatus;
import com.multi.vidulum.bank_data_ingestion.staging.domain.StagingSessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
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

    private ClassificationStatus classificationStatus;

    // ============ Summary (denormalized for quick access) ============

    private int totalTransactions;
    private int validTransactions;
    private int invalidTransactions;
    private int duplicateTransactions;
    private int unmappedTransactions;

    // ============ Classification Results ============
    //
    // No tokens and no estimated cost here any more, and that is the point of the rename: those two
    // fields were the supplier's units in a stored document, so a classifier without a language
    // model would have had to write zeros into them forever. Nothing ever read them - the cost in
    // the HTTP response comes straight from the engine's own answer - so removing them changes no
    // payload and lets the contract stop promising telemetry (F67).

    private Instant classificationStartedAt;
    private Instant classificationCompletedAt;

    // ============ Import Job Results ============

    private String importJobId;
    private Instant importStartedAt;
    private Instant importCompletedAt;
    private Integer importedTransactionsCount;

    /**
     * Turns every save into a conditional update (task F57). A staging session is a state machine -
     * HAS_UNMAPPED_CATEGORIES through READY_FOR_IMPORT to COMPLETED - and four command handlers
     * (ai-categorize, force-uncategorized, revalidate, start-import) load this document, change its
     * status or summary and store it whole. Two of them arriving together used to let the later one
     * decide, so a session could report counts from one run and a status from another.
     *
     * <p>No threading needed here, unlike the aggregates: the handlers pass this entity itself
     * around, so the version they read is the version they save.
     */
    @Version
    private Long version;

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
                .classificationStatus(ClassificationStatus.NOT_STARTED)
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
            if (classificationStatus == ClassificationStatus.COMPLETED) {
                this.status = StagingSessionStatus.SUGGESTIONS_READY;
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
    public void startClassification() {
        this.classificationStatus = ClassificationStatus.IN_PROGRESS;
        this.classificationStartedAt = Instant.now();
        this.lastModifiedAt = Instant.now();
    }

    /**
     * Marks classification as completed. Takes no cost: what a classification cost is the engine's
     * business and the caller's answer, not the session's state.
     */
    public void completeClassification() {
        this.classificationStatus = ClassificationStatus.COMPLETED;
        this.classificationCompletedAt = Instant.now();
        this.lastModifiedAt = Instant.now();

        // Update session status to SUGGESTIONS_READY if there are still unmapped
        if (unmappedTransactions > 0) {
            this.status = StagingSessionStatus.SUGGESTIONS_READY;
        }
    }

    /**
     * Marks AI categorization as failed.
     */
    public void failClassification() {
        this.classificationStatus = ClassificationStatus.FAILED;
        this.lastModifiedAt = Instant.now();
    }

    /**
     * Marks AI categorization as skipped (user chose manual mapping).
     */
    public void skipClassification() {
        this.classificationStatus = ClassificationStatus.SKIPPED;
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
