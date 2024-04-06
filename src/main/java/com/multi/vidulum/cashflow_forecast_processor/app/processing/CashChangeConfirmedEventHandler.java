package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.PAID;

@Component
@AllArgsConstructor
public class CashChangeConfirmedEventHandler implements CashFlowEventHandler<CashFlowEvent.CashChangeConfirmedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CashChangeConfirmedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        CashFlowMonthlyForecast.CashChangeLocation location = statement.locate(event.cashChangeId())
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Cannot find CashChange with id[%s]", event.cashChangeId())));

        statement.getForecasts().compute(location.yearMonth(), (yearMonth1, cashFlowMonthlyForecast) -> {
            Transaction currentTransaction = location.transaction();
            Transaction updatedTransaction = new Transaction(
                    new TransactionDetails(
                            currentTransaction.transactionDetails().getCashChangeId(),
                            currentTransaction.transactionDetails().getName(),
                            currentTransaction.transactionDetails().getMoney(),
                            currentTransaction.transactionDetails().getCreated(),
                            currentTransaction.transactionDetails().getDueDate(),
                            event.endDate()),
                    PAID
            );

            if (Type.INFLOW.equals(location.type())) {
                CategoryName categoryName = cashFlowMonthlyForecast.findCashCategoryForCashChange(event.cashChangeId(), cashFlowMonthlyForecast.getCategorizedInFlows())
                        .map(CashCategory::getCategoryName)
                        .orElseThrow(() -> new IllegalArgumentException(""));

                cashFlowMonthlyForecast.removeFromInflows(categoryName, currentTransaction);
                cashFlowMonthlyForecast.addToInflows(categoryName, updatedTransaction);

            } else {
                CategoryName categoryName = cashFlowMonthlyForecast.findCashCategoryForCashChange(event.cashChangeId(), cashFlowMonthlyForecast.getCategorizedOutFlows())
                        .map(CashCategory::getCategoryName)
                        .orElseThrow(() -> new IllegalArgumentException(""));

                cashFlowMonthlyForecast.removeFromOutflows(categoryName, currentTransaction);
                cashFlowMonthlyForecast.addToOutflows(categoryName, updatedTransaction);
            }
            return cashFlowMonthlyForecast;
        });

        statement.updateStats();

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
    }
}
