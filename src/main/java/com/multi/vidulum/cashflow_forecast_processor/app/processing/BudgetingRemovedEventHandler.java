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

@Slf4j
@Component
@AllArgsConstructor
public class BudgetingRemovedEventHandler implements CashFlowEventHandler<CashFlowEvent.BudgetingRemovedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.BudgetingRemovedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        // Update CategoryNode in categoryStructure - remove budgeting
        List<CategoryNode> categoryNodes = Type.INFLOW.equals(event.categoryType())
                ? statement.getCategoryStructure().inflowCategoryStructure()
                : statement.getCategoryStructure().outflowCategoryStructure();

        CategoryNode categoryNode = findCategoryNode(categoryNodes, event.categoryName());
        if (categoryNode != null) {
            categoryNode.setBudgeting(null);
        }

        // Update CashCategory in all forecasts - remove budgeting
        statement.getForecasts().values().forEach(cashFlowMonthlyForecast -> {
            List<CashCategory> categories = Type.INFLOW.equals(event.categoryType())
                    ? cashFlowMonthlyForecast.getCategorizedInFlows()
                    : cashFlowMonthlyForecast.getCategorizedOutFlows();

            CashCategory cashCategory = findCashCategory(categories, event.categoryName());
            if (cashCategory != null) {
                cashCategory.setBudgeting(null);
            }
        });

        statementRepository.save(statement);
        log.info("Budgeting removed for category [{}] in cashflow [{}]", event.categoryName().name(), event.cashFlowId().id());
    }

    private CategoryNode findCategoryNode(List<CategoryNode> categoryNodes, CategoryName categoryName) {
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

    private CashCategory findCashCategory(List<CashCategory> categories, CategoryName categoryName) {
        Stack<CashCategory> stack = new Stack<>();
        categories.forEach(stack::push);
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
