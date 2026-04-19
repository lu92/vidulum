package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionStatus;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagingSessionEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB repository for StagingSession entities.
 */
public interface StagingSessionMongoRepository extends MongoRepository<StagingSessionEntity, String> {

    /**
     * Find by session ID.
     */
    Optional<StagingSessionEntity> findBySessionId(String sessionId);

    /**
     * Find all sessions for a CashFlow.
     */
    List<StagingSessionEntity> findByCashFlowId(String cashFlowId);

    /**
     * Find all sessions for a CashFlow with specific status.
     */
    List<StagingSessionEntity> findByCashFlowIdAndStatus(String cashFlowId, StagingSessionStatus status);

    /**
     * Find all sessions for a CashFlow ordered by creation date (newest first).
     */
    List<StagingSessionEntity> findByCashFlowIdOrderByCreatedAtDesc(String cashFlowId);

    /**
     * Find all sessions created by a user.
     */
    List<StagingSessionEntity> findByCreatedByUserId(String userId);

    /**
     * Find by transformation ID.
     */
    Optional<StagingSessionEntity> findByTransformationId(String transformationId);

    /**
     * Delete by session ID.
     */
    void deleteBySessionId(String sessionId);

    /**
     * Delete all sessions for a CashFlow.
     */
    long deleteByCashFlowId(String cashFlowId);

    /**
     * Check if a session exists for a transformation.
     */
    boolean existsByTransformationId(String transformationId);

    /**
     * Count sessions by status.
     */
    long countByCashFlowIdAndStatus(String cashFlowId, StagingSessionStatus status);
}
