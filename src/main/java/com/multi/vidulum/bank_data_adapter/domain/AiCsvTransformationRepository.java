package com.multi.vidulum.bank_data_adapter.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for AI CSV transformation audit records.
 */
public interface AiCsvTransformationRepository extends MongoRepository<AiCsvTransformationDocument, String> {

    /**
     * Find all transformations for a user, ordered by creation date (newest first).
     */
    List<AiCsvTransformationDocument> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * Check if a file with given hash has already been processed by this user.
     */
    Optional<AiCsvTransformationDocument> findByOriginalFileHashAndUserId(String hash, String userId);

    /**
     * Find transformations by user and import status.
     */
    List<AiCsvTransformationDocument> findByUserIdAndImportStatus(String userId, ImportStatus importStatus);

    /**
     * Find transformation by ID and user (for security - users can only access their own).
     */
    Optional<AiCsvTransformationDocument> findByIdAndUserId(String id, String userId);
}
