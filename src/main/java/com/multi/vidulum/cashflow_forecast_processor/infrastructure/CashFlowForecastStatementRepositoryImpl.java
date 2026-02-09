package com.multi.vidulum.cashflow_forecast_processor.infrastructure;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatementRepository;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity.CashFlowForecastStatementEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Primary
@Slf4j
@RequiredArgsConstructor
public class CashFlowForecastStatementRepositoryImpl implements CashFlowForecastStatementRepository {

    private final CashFlowForecastStatementMongoRepository mongoRepository;

    @Override
    public Optional<CashFlowForecastStatement> findByCashFlowId(CashFlowId cashFlowId) {
        return mongoRepository.findByCashFlowId(cashFlowId.id())
                .map(CashFlowForecastStatementEntity::toDomain);
    }

    @Override
    public void save(CashFlowForecastStatement statement) {
        CashFlowForecastStatementEntity entity = CashFlowForecastStatementEntity.fromDomain(statement);
        mongoRepository.save(entity);
        log.info("CashFlowForecastStatement for cashFlowId[{}] with checksum[{}] saved to MongoDB",
                statement.getCashFlowId().id(),
                statement.getLastMessageChecksum() != null ? statement.getLastMessageChecksum().checksum() : "null");
    }
}
