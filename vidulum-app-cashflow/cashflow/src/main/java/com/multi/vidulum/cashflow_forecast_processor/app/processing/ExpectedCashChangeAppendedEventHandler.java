package com.multi.vidulum.cashflow_forecast_processor.app.processing;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.EXPECTED;

@Component
@AllArgsConstructor
public class ExpectedCashChangeAppendedEventHandler implements CashFlowEventHandler<CashFlowEvent.ExpectedCashChangeAppendedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;
    private final ExpectedCashChangeProjector projector;

    @Override
    public void handle(CashFlowEvent.ExpectedCashChangeAppendedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowNotFoundException(event.cashFlowId()));

        // The same per-item projection the batch event uses - see ExpectedCashChangeProjector. A
        // single append is a batch of one as far as the statement is concerned.
        projector.project(statement, asAppended(event), event.created());

        statement.updateStats();
        updateSyncMetadata(statement, event);
        statementRepository.save(statement);
    }

    private static CashFlowEvent.ExpectedCashChangesBatchAppendedEvent.AppendedExpectedCashChange asAppended(
            CashFlowEvent.ExpectedCashChangeAppendedEvent event) {
        return new CashFlowEvent.ExpectedCashChangesBatchAppendedEvent.AppendedExpectedCashChange(
                event.cashChangeId(),
                event.name(),
                event.description(),
                event.money(),
                event.type(),
                event.categoryName(),
                event.dueDate(),
                event.selfTransfer()
        );
    }
}
