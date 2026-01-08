package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.ImportJobEntity;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@AllArgsConstructor
public class ImportJobRepositoryImpl implements ImportJobRepository {

    private final ImportJobMongoRepository mongoRepository;

    @Override
    public ImportJob save(ImportJob job) {
        ImportJobEntity entity = ImportJobEntity.fromDomain(job);
        ImportJobEntity saved = mongoRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<ImportJob> findById(ImportJobId id) {
        return mongoRepository.findByJobId(id.id())
                .map(ImportJobEntity::toDomain);
    }

    @Override
    public Optional<ImportJob> findByCashFlowIdAndStagingSessionId(CashFlowId cashFlowId, StagingSessionId stagingSessionId) {
        return mongoRepository.findByCashFlowIdAndStagingSessionId(cashFlowId.id(), stagingSessionId.id())
                .map(ImportJobEntity::toDomain);
    }

    @Override
    public List<ImportJob> findByCashFlowId(CashFlowId cashFlowId) {
        return mongoRepository.findByCashFlowId(cashFlowId.id())
                .stream()
                .map(ImportJobEntity::toDomain)
                .toList();
    }

    @Override
    public List<ImportJob> findByCashFlowIdAndStatusIn(CashFlowId cashFlowId, List<ImportJobStatus> statuses) {
        List<String> statusStrings = statuses.stream()
                .map(ImportJobStatus::name)
                .toList();

        return mongoRepository.findByCashFlowIdAndStatusIn(cashFlowId.id(), statusStrings)
                .stream()
                .map(ImportJobEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<ImportJob> findActiveJobByCashFlowId(CashFlowId cashFlowId) {
        List<String> activeStatuses = List.of(
                ImportJobStatus.PENDING.name(),
                ImportJobStatus.PROCESSING.name()
        );

        return mongoRepository.findByCashFlowIdAndStatusIn(cashFlowId.id(), activeStatuses)
                .stream()
                .findFirst()
                .map(ImportJobEntity::toDomain);
    }

    @Override
    public boolean existsActiveByStagingSessionId(StagingSessionId stagingSessionId) {
        List<String> activeStatuses = List.of(
                ImportJobStatus.PENDING.name(),
                ImportJobStatus.PROCESSING.name()
        );

        return mongoRepository.existsByStagingSessionIdAndStatusIn(stagingSessionId.id(), activeStatuses);
    }
}
