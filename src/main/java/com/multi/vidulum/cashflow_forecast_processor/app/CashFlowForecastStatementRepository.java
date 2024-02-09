package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public interface CashFlowForecastStatementRepository {
    Optional<CashFlowForecastStatement> findByCashFlowId(CashFlowId cashFlowId);

    void save(CashFlowForecastStatement statement);

    @Component
    @Slf4j
    public static class InMemory implements CashFlowForecastStatementRepository {
        private Map<CashFlowId, CashFlowForecastStatement> memory = new ConcurrentHashMap<>();

        @Override
        public Optional<CashFlowForecastStatement> findByCashFlowId(CashFlowId cashFlowId) {
            return Optional.ofNullable(memory.get(cashFlowId));
        }

        @Override
        public void save(CashFlowForecastStatement statement) {
            memory.put(statement.getCashFlowId(), statement);
            log.info("CashFlowForecastStatement checksum[{}] saved in DB", statement.getLastMessageChecksum().checksum());
        }
    }
}
