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
 * Handles CategoryMovedEvent by updating:
 * <ul>
 *   <li>CurrentCategoryStructure - the template for creating new monthly forecasts</li>
 *   <li>All existing CashFlowMonthlyForecasts - moving the category tree in each month</li>
 * </ul>
 * <p>
 * When a category is moved, all its subcategories move with it, preserving the tree structure.
 * Transactions within those categories remain associated with them.
 */
@Slf4j
@Component
@AllArgsConstructor
public class CategoryMovedEventHandler implements CashFlowEventHandler<CashFlowEvent.CategoryMovedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CategoryMovedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        // 1. Update CurrentCategoryStructure (template for new months)
        updateCategoryStructure(statement.getCategoryStructure(), event);

        // 2. Update all monthly forecasts
        statement.getForecasts().values().forEach(monthlyForecast -> {
            moveCategoryInForecast(monthlyForecast, event);
        });

        // 3. Recalculate stats (in case category sums affect totals)
        statement.updateStats();

        updateSyncMetadata(statement, event);
        statementRepository.save(statement);

        log.info("Category [{}] moved from [{}] to [{}] in forecast statement for cashflow [{}]",
                event.categoryName().name(),
                event.oldParentCategoryName().isDefined() ? event.oldParentCategoryName().name() : "root",
                event.newParentCategoryName().isDefined() ? event.newParentCategoryName().name() : "root",
                event.cashFlowId().id());
    }

    /**
     * Updates the CurrentCategoryStructure by moving the CategoryNode.
     */
    private void updateCategoryStructure(CurrentCategoryStructure categoryStructure, CashFlowEvent.CategoryMovedEvent event) {
        List<CategoryNode> categoryNodes = Type.INFLOW.equals(event.categoryType())
                ? categoryStructure.inflowCategoryStructure()
                : categoryStructure.outflowCategoryStructure();

        // Find and remove the node from old location
        CategoryNode movingNode = findAndRemoveNode(categoryNodes, event.categoryName(), event.oldParentCategoryName());

        if (movingNode == null) {
            log.warn("CategoryNode [{}] not found in category structure for cashflow [{}]",
                    event.categoryName().name(), event.cashFlowId().id());
            return;
        }

        // Add to new location
        if (event.newParentCategoryName().isDefined()) {
            // Add as child of new parent
            CategoryNode newParent = findCategoryNodeByName(categoryNodes, event.newParentCategoryName());
            if (newParent != null) {
                movingNode.setParentCategoryNode(newParent);
                newParent.getNodes().add(movingNode);
            } else {
                log.warn("New parent CategoryNode [{}] not found, adding to root",
                        event.newParentCategoryName().name());
                movingNode.setParentCategoryNode(null);
                categoryNodes.add(movingNode);
            }
        } else {
            // Add to root level
            movingNode.setParentCategoryNode(null);
            categoryNodes.add(movingNode);
        }
    }

    /**
     * Moves a CashCategory within a monthly forecast.
     */
    private void moveCategoryInForecast(CashFlowMonthlyForecast monthlyForecast, CashFlowEvent.CategoryMovedEvent event) {
        List<CashCategory> categories = Type.INFLOW.equals(event.categoryType())
                ? monthlyForecast.getCategorizedInFlows()
                : monthlyForecast.getCategorizedOutFlows();

        // Find and remove from old location
        CashCategory movingCategory = findAndRemoveCategory(categories, event.categoryName(), event.oldParentCategoryName());

        if (movingCategory == null) {
            log.warn("CashCategory [{}] not found in monthly forecast for cashflow [{}]",
                    event.categoryName().name(), event.cashFlowId().id());
            return;
        }

        // Add to new location
        if (event.newParentCategoryName().isDefined()) {
            // Add as child of new parent
            CashCategory newParent = findCategoryByName(categories, event.newParentCategoryName());
            if (newParent != null) {
                newParent.getSubCategories().add(movingCategory);
            } else {
                log.warn("New parent CashCategory [{}] not found, adding to root",
                        event.newParentCategoryName().name());
                categories.add(movingCategory);
            }
        } else {
            // Add to root level
            categories.add(movingCategory);
        }
    }

    /**
     * Finds and removes a CategoryNode from the tree structure.
     *
     * @return The removed CategoryNode, or null if not found
     */
    private CategoryNode findAndRemoveNode(List<CategoryNode> categoryNodes, CategoryName categoryName, CategoryName oldParentName) {
        if (!oldParentName.isDefined()) {
            // Was at root level
            for (int i = 0; i < categoryNodes.size(); i++) {
                if (categoryNodes.get(i).getCategoryName().equals(categoryName)) {
                    return categoryNodes.remove(i);
                }
            }
        } else {
            // Was under a parent
            CategoryNode oldParent = findCategoryNodeByName(categoryNodes, oldParentName);
            if (oldParent != null) {
                List<CategoryNode> childNodes = oldParent.getNodes();
                for (int i = 0; i < childNodes.size(); i++) {
                    if (childNodes.get(i).getCategoryName().equals(categoryName)) {
                        return childNodes.remove(i);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds and removes a CashCategory from the tree structure.
     *
     * @return The removed CashCategory, or null if not found
     */
    private CashCategory findAndRemoveCategory(List<CashCategory> categories, CategoryName categoryName, CategoryName oldParentName) {
        if (!oldParentName.isDefined()) {
            // Was at root level
            for (int i = 0; i < categories.size(); i++) {
                if (categories.get(i).getCategoryName().equals(categoryName)) {
                    return categories.remove(i);
                }
            }
        } else {
            // Was under a parent
            CashCategory oldParent = findCategoryByName(categories, oldParentName);
            if (oldParent != null) {
                List<CashCategory> subCategories = oldParent.getSubCategories();
                for (int i = 0; i < subCategories.size(); i++) {
                    if (subCategories.get(i).getCategoryName().equals(categoryName)) {
                        return subCategories.remove(i);
                    }
                }
            }
        }
        return null;
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
