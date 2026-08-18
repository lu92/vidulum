package com.multi.vidulum.cashflow_forecast_processor.infrastructure;
import com.multi.vidulum.common.CashFlowId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CashFlowForecastMongoRepository extends MongoRepository<CashFlowForecastEntity, String> {
    Optional<CashFlowForecastEntity> findByCashFlowId(String id);
}
