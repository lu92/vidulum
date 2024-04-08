package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
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
                    .subCategories(new LinkedList<>())
                    .groupedTransactions(new GroupedTransactions())
                    .totalPaidValue(Money.zero(statement.getBankAccountNumber().denomination().getId()))
                    .build();

            List<CashCategory> properCategories = findProperCategories(event, cashFlowMonthlyForecast);
            properCategories.add(newCashCategory);
        });

        statement.updateStats();

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
    }

    private List<CashCategory> findProperCategories(CashFlowEvent.CategoryCreatedEvent event, CashFlowMonthlyForecast cashFlowMonthlyForecast) {
        if (event.parentCategoryName().isDefined()) {
            CashCategory parentCashCategory = Type.INFLOW.equals(event.type()) ?
                    cashFlowMonthlyForecast.findCategoryInflowsByCategoryName(event.parentCategoryName()).orElseThrow() :
                    cashFlowMonthlyForecast.findCategoryOutflowsByCategoryName(event.parentCategoryName()).orElseThrow();
            return parentCashCategory.getSubCategories();
        } else {
            return Type.INFLOW.equals(event.type()) ?
                    cashFlowMonthlyForecast.getCategorizedInFlows() :
                    cashFlowMonthlyForecast.getCategorizedOutFlows();
        }
    }
}
