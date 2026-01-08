package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.domain.ValidationStatus;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagedTransactionEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StagedTransactionMongoRepository extends MongoRepository<StagedTransactionEntity, String> {

    Optional<StagedTransactionEntity> findByStagedTransactionId(String stagedTransactionId);

    List<StagedTransactionEntity> findByStagingSessionId(String stagingSessionId);

    List<StagedTransactionEntity> findByCashFlowId(String cashFlowId);

    @Query("{'cashFlowId': ?0, 'originalData.bankTransactionId': ?1}")
    Optional<StagedTransactionEntity> findByCashFlowIdAndBankTransactionId(
            String cashFlowId,
            String bankTransactionId
    );

    long deleteByStagingSessionId(String stagingSessionId);

    long deleteByCashFlowId(String cashFlowId);

    long countByStagingSessionId(String stagingSessionId);

    long countByStagingSessionIdAndValidationStatus(String stagingSessionId, ValidationStatus status);
}
