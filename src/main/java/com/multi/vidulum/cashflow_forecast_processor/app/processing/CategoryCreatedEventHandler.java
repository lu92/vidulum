package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

@Slf4j
@Component
@AllArgsConstructor
public class CategoryCreatedEventHandler implements CashFlowEventHandler<CashFlowEvent.CategoryCreatedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CategoryCreatedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        fun(statement.getCategoryStructure(), event);

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
        log.info("Projection of cashflow [{}] amended with new category [{}]", event.cashFlowId().id(), event.categoryName().name());
    }

    private void fun(CurrentCategoryStructure categoryStructure, CashFlowEvent.CategoryCreatedEvent event) {
        CategoryNode newCategoryNode = null;

        if (event.parentCategoryName().equals(CategoryName.NOT_DEFINED)) {
            newCategoryNode = new CategoryNode(null, event.categoryName(), new LinkedList<>());
            if (event.type() == Type.INFLOW) {
                categoryStructure.inflowCategoryStructure().add(newCategoryNode);
            } else {
                categoryStructure.outflowCategoryStructure().add(newCategoryNode);
            }
        } else {
            CategoryNode parent = findParentNode(event.type() == Type.INFLOW ?
                            categoryStructure.inflowCategoryStructure() :
                            categoryStructure.outflowCategoryStructure(),
                    event.parentCategoryName());
            newCategoryNode = new CategoryNode(parent, event.categoryName(), new LinkedList<>());

            assert parent != null;
            parent.nodes().add(newCategoryNode);
        }
    }

    private CategoryNode findParentNode(List<CategoryNode> categoryNodes, CategoryName parentCategoryName) {
        Stack<CategoryNode> stack = new Stack<>();
        categoryNodes.forEach(stack::push);
        while (!stack.isEmpty()) {
            CategoryNode takenCashCategoryNode = stack.pop();
            if (takenCashCategoryNode.categoryName().equals(parentCategoryName)) {
                return takenCashCategoryNode;
            }
            takenCashCategoryNode.nodes().forEach(stack::push);
        }
        return null;
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
