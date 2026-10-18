package com.multi.vidulum.cashflow_forecast_processor.infrastructure;

import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatementRepository;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity.CashFlowForecastStatementEntity;
import com.multi.vidulum.common.CashFlowId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link CashFlowForecastStatementRepository} for unit tests.
 * Uses {@link CashFlowForecastStatementEntity} for round-trip (fromDomain/toDomain).
 */
class InMemoryForecastStatementRepository implements CashFlowForecastStatementRepository {

    private final Map<String, CashFlowForecastStatementEntity> store = new ConcurrentHashMap<>();

    @Override
    public Optional<CashFlowForecastStatement> findByCashFlowId(CashFlowId cashFlowId) {
        return Optional.ofNullable(store.get(cashFlowId.id()))
                .map(CashFlowForecastStatementEntity::toDomain);
    }

    @Override
    public void save(CashFlowForecastStatement statement) {
        CashFlowForecastStatementEntity entity = CashFlowForecastStatementEntity.fromDomain(statement);
        store.put(entity.getCashFlowId(), entity);
    }

    long count() {
        return store.size();
    }

    void deleteAll() {
        store.clear();
    }
}
