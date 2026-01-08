package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagedTransactionEntity;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@AllArgsConstructor
public class StagedTransactionRepositoryImpl implements StagedTransactionRepository {

    private final StagedTransactionMongoRepository mongoRepository;

    @Override
    public StagedTransaction save(StagedTransaction stagedTransaction) {
        StagedTransactionEntity entity = StagedTransactionEntity.fromDomain(stagedTransaction);
        StagedTransactionEntity saved = mongoRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public List<StagedTransaction> saveAll(List<StagedTransaction> stagedTransactions) {
        List<StagedTransactionEntity> entities = stagedTransactions.stream()
                .map(StagedTransactionEntity::fromDomain)
                .toList();
        List<StagedTransactionEntity> saved = mongoRepository.saveAll(entities);
        return saved.stream()
                .map(StagedTransactionEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<StagedTransaction> findById(StagedTransactionId stagedTransactionId) {
        return mongoRepository.findByStagedTransactionId(stagedTransactionId.id())
                .map(StagedTransactionEntity::toDomain);
    }

    @Override
    public List<StagedTransaction> findByStagingSessionId(StagingSessionId stagingSessionId) {
        return mongoRepository.findByStagingSessionId(stagingSessionId.id())
                .stream()
                .map(StagedTransactionEntity::toDomain)
                .toList();
    }

    @Override
    public List<StagedTransaction> findByCashFlowId(CashFlowId cashFlowId) {
        return mongoRepository.findByCashFlowId(cashFlowId.id())
                .stream()
                .map(StagedTransactionEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<StagedTransaction> findByCashFlowIdAndBankTransactionId(
            CashFlowId cashFlowId,
            String bankTransactionId
    ) {
        return mongoRepository.findByCashFlowIdAndBankTransactionId(cashFlowId.id(), bankTransactionId)
                .map(StagedTransactionEntity::toDomain);
    }

    @Override
    public long deleteByStagingSessionId(StagingSessionId stagingSessionId) {
        return mongoRepository.deleteByStagingSessionId(stagingSessionId.id());
    }

    @Override
    public long deleteByCashFlowId(CashFlowId cashFlowId) {
        return mongoRepository.deleteByCashFlowId(cashFlowId.id());
    }

    @Override
    public long countByStagingSessionId(StagingSessionId stagingSessionId) {
        return mongoRepository.countByStagingSessionId(stagingSessionId.id());
    }

    @Override
    public long countValidByStagingSessionId(StagingSessionId stagingSessionId) {
        return mongoRepository.countByStagingSessionIdAndValidationStatus(
                stagingSessionId.id(),
                ValidationStatus.VALID
        );
    }

    @Override
    public long countDuplicatesByStagingSessionId(StagingSessionId stagingSessionId) {
        return mongoRepository.countByStagingSessionIdAndValidationStatus(
                stagingSessionId.id(),
                ValidationStatus.DUPLICATE
        );
    }

    @Override
    public long countInvalidByStagingSessionId(StagingSessionId stagingSessionId) {
        return mongoRepository.countByStagingSessionIdAndValidationStatus(
                stagingSessionId.id(),
                ValidationStatus.INVALID
        );
    }
}
