package com.multi.vidulum.cashflow_forecast_processor.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CashFlowForecastMongoRepository extends MongoRepository<CashFlowForecastEntity, String> {
    Optional<CashFlowForecastEntity> findByCashFlowId(String id);
}
