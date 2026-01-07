package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Stack;

/**
 * Handles CategoryUnarchivedEvent by updating the forecast statement's category structure.
 * Unarchived categories are marked with archived=false and validTo set to null.
 * This makes the category available again for new transaction creation in the UI.
 */
@Slf4j
@Component
@AllArgsConstructor
public class CategoryUnarchivedEventHandler implements CashFlowEventHandler<CashFlowEvent.CategoryUnarchivedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CategoryUnarchivedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        // Update category structure
        updateCategoryStructure(statement.getCategoryStructure(), event);

        // Update all monthly forecasts
        statement.getForecasts().values().forEach(cashFlowMonthlyForecast -> {
            List<CashCategory> categories = Type.INFLOW.equals(event.categoryType())
                    ? cashFlowMonthlyForecast.getCategorizedInFlows()
                    : cashFlowMonthlyForecast.getCategorizedOutFlows();

            CashCategory category = findCategoryByName(categories, event.categoryName());
            if (category != null) {
                category.unarchive();
            }
        });

        updateSyncMetadata(statement, event);
        statementRepository.save(statement);
        log.info("Category [{}] of type [{}] unarchived in forecast statement for cashflow [{}]",
                event.categoryName().name(), event.categoryType(), event.cashFlowId().id());
    }

    private void updateCategoryStructure(CurrentCategoryStructure categoryStructure, CashFlowEvent.CategoryUnarchivedEvent event) {
        List<CategoryNode> categoryNodes = Type.INFLOW.equals(event.categoryType())
                ? categoryStructure.inflowCategoryStructure()
                : categoryStructure.outflowCategoryStructure();

        CategoryNode node = findCategoryNodeByName(categoryNodes, event.categoryName());
        if (node != null) {
            node.unarchive();
        }
    }

    private CategoryNode findCategoryNodeByName(List<CategoryNode> categoryNodes, CategoryName categoryName) {
        Stack<CategoryNode> stack = new Stack<>();
        categoryNodes.forEach(stack::push);
        while (!stack.isEmpty()) {
            CategoryNode node = stack.pop();
            if (node.getCategoryName().equals(categoryName)) {
                return node;
            }
            node.getNodes().forEach(stack::push);
        }
        return null;
    }

    private CashCategory findCategoryByName(List<CashCategory> cashCategories, CategoryName categoryName) {
        Stack<CashCategory> stack = new Stack<>();
        cashCategories.forEach(stack::push);
        while (!stack.isEmpty()) {
            CashCategory category = stack.pop();
            if (category.getCategoryName().equals(categoryName)) {
                return category;
            }
            category.getSubCategories().forEach(stack::push);
        }
        return null;
    }
}
