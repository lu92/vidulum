package com.multi.vidulum.cashflow_forecast_processor.infrastructure;

import com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity.CashFlowForecastStatementEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CashFlowForecastStatementMongoRepository extends MongoRepository<CashFlowForecastStatementEntity, String> {

    Optional<CashFlowForecastStatementEntity> findByCashFlowId(String cashFlowId);
}
