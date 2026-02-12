package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.util.Optional;

/**
 * Repository for CashFlowForecastStatement persistence.
 * <p>
 * Primary implementation uses MongoDB ({@link com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastStatementRepositoryImpl}).
 */
public interface CashFlowForecastStatementRepository {

    /**
     * Find a CashFlowForecastStatement by its CashFlowId.
     *
     * @param cashFlowId the CashFlowId to search for
     * @return Optional containing the statement if found, empty otherwise
     */
    Optional<CashFlowForecastStatement> findByCashFlowId(CashFlowId cashFlowId);

    /**
     * Save or update a CashFlowForecastStatement.
     *
     * @param statement the statement to save
     */
    void save(CashFlowForecastStatement statement);
}
