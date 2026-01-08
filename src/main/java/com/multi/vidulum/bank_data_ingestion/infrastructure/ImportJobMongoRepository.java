package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.ImportJobEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB repository for ImportJob entities.
 */
public interface ImportJobMongoRepository extends MongoRepository<ImportJobEntity, String> {

    /**
     * Find by job ID.
     */
    Optional<ImportJobEntity> findByJobId(String jobId);

    /**
     * Find by CashFlow ID and staging session ID.
     */
    Optional<ImportJobEntity> findByCashFlowIdAndStagingSessionId(String cashFlowId, String stagingSessionId);

    /**
     * Find all jobs for a CashFlow.
     */
    List<ImportJobEntity> findByCashFlowId(String cashFlowId);

    /**
     * Find jobs for a CashFlow with specific statuses.
     */
    List<ImportJobEntity> findByCashFlowIdAndStatusIn(String cashFlowId, List<String> statuses);

    /**
     * Find active jobs (PENDING or PROCESSING) for a CashFlow.
     */
    Optional<ImportJobEntity> findByCashFlowIdAndStatusIn(String cashFlowId, String status1, String status2);

    /**
     * Check if there's an active job for a staging session.
     */
    boolean existsByStagingSessionIdAndStatusIn(String stagingSessionId, List<String> statuses);
}
