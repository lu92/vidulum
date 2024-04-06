package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class CategoryCreatedEventHandler implements CashFlowEventHandler<CashFlowEvent.CategoryCreatedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CategoryCreatedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        statement.getForecasts().values().forEach(cashFlowMonthlyForecast -> {

            CashCategory newCashCategory = CashCategory.builder()
                    .categoryName(event.categoryName())
                    .category(new Category(event.categoryName().name()))
                    .subCategories(List.of())
                    .groupedTransactions(new GroupedTransactions())
                    .totalPaidValue(Money.zero(statement.getBankAccountNumber().denomination().getId()))
                    .build();

            if (Type.INFLOW.equals(event.type())) {
                cashFlowMonthlyForecast.getCategorizedInFlows().add(newCashCategory);
            } else {
                cashFlowMonthlyForecast.getCategorizedOutFlows().add(newCashCategory);
            }

        });

        statement.updateStats();

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
    }
}
