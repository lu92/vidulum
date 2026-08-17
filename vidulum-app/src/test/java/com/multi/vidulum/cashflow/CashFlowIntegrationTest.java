package com.multi.vidulum.cashflow;

import com.multi.vidulum.cashflow.domain.CashFlowEventEmitter;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.infrastructure.CashFlowMongoRepository;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatementRepository;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastMongoRepository;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test base class for cashflow-related tests.
 * Extends {@link IntegrationTest} with cashflow-specific repositories and helpers.
 *
 * <p>When the cashflow packages are extracted to a separate Maven module,
 * this class and its subclasses move together.</p>
 */
public abstract class CashFlowIntegrationTest extends IntegrationTest {

    @Autowired
    protected DomainCashFlowRepository domainCashFlowRepository;

    @Autowired
    protected CashFlowMongoRepository cashFlowMongoRepository;

    @Autowired
    protected CashFlowForecastMongoRepository cashFlowForecastMongoRepository;

    @Autowired
    protected CashFlowForecastStatementRepository statementRepository;

    @Autowired
    protected CashFlowEventEmitter cashFlowEventEmitter;

    protected boolean lastEventIsProcessed(CashFlowId cashFlowId, Checksum lastEventChecksum) {
        return statementRepository.findByCashFlowId(cashFlowId)
                .map(statement -> statement.getLastMessageChecksum().equals(lastEventChecksum))
                .orElse(false);
    }
}
