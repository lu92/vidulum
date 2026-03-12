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

    /**
     * Result of removing an element from a list, containing the element and its original index.
     */
    private record RemovalResult<T>(T element, int oldIndex, List<T> sourceList) {}

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
        RemovalResult<CategoryNode> removalResult = findAndRemoveNode(categoryNodes, event.categoryName(), event.oldParentCategoryName());

        if (removalResult == null) {
            log.warn("CategoryNode [{}] not found in category structure for cashflow [{}]",
                    event.categoryName().name(), event.cashFlowId().id());
            return;
        }

        CategoryNode movingNode = removalResult.element();
        List<CategoryNode> targetList;

        // Add to new location at specified position or at end
        if (event.newParentCategoryName().isDefined()) {
            // Add as child of new parent
            CategoryNode newParent = findCategoryNodeByName(categoryNodes, event.newParentCategoryName());
            if (newParent != null) {
                movingNode.setParentCategoryNode(newParent);
                targetList = newParent.getNodes();
            } else {
                log.warn("New parent CategoryNode [{}] not found, adding to root",
                        event.newParentCategoryName().name());
                movingNode.setParentCategoryNode(null);
                targetList = categoryNodes;
            }
        } else {
            // Add to root level
            movingNode.setParentCategoryNode(null);
            targetList = categoryNodes;
        }

        // Calculate adjusted position for same-list moves
        Integer adjustedPosition = calculateAdjustedPosition(
                event.newPosition(), removalResult.oldIndex(), removalResult.sourceList(), targetList);
        addAtPosition(targetList, movingNode, adjustedPosition);
    }

    /**
     * Moves a CashCategory within a monthly forecast.
     */
    private void moveCategoryInForecast(CashFlowMonthlyForecast monthlyForecast, CashFlowEvent.CategoryMovedEvent event) {
        List<CashCategory> categories = Type.INFLOW.equals(event.categoryType())
                ? monthlyForecast.getCategorizedInFlows()
                : monthlyForecast.getCategorizedOutFlows();

        // Find and remove from old location
        RemovalResult<CashCategory> removalResult = findAndRemoveCategory(categories, event.categoryName(), event.oldParentCategoryName());

        if (removalResult == null) {
            log.warn("CashCategory [{}] not found in monthly forecast for cashflow [{}]",
                    event.categoryName().name(), event.cashFlowId().id());
            return;
        }

        CashCategory movingCategory = removalResult.element();
        List<CashCategory> targetList;

        // Add to new location at specified position or at end
        if (event.newParentCategoryName().isDefined()) {
            // Add as child of new parent
            CashCategory newParent = findCategoryByName(categories, event.newParentCategoryName());
            if (newParent != null) {
                targetList = newParent.getSubCategories();
            } else {
                log.warn("New parent CashCategory [{}] not found, adding to root",
                        event.newParentCategoryName().name());
                targetList = categories;
            }
        } else {
            // Add to root level
            targetList = categories;
        }

        // Calculate adjusted position for same-list moves
        Integer adjustedPosition = calculateAdjustedPosition(
                event.newPosition(), removalResult.oldIndex(), removalResult.sourceList(), targetList);
        addCashCategoryAtPosition(targetList, movingCategory, adjustedPosition);
    }

    /**
     * Finds and removes a CategoryNode from the tree structure.
     *
     * @return RemovalResult containing the removed node, its old index, and source list; or null if not found
     */
    private RemovalResult<CategoryNode> findAndRemoveNode(List<CategoryNode> categoryNodes, CategoryName categoryName, CategoryName oldParentName) {
        List<CategoryNode> sourceList;
        if (!oldParentName.isDefined()) {
            // Was at root level
            sourceList = categoryNodes;
        } else {
            // Was under a parent
            CategoryNode oldParent = findCategoryNodeByName(categoryNodes, oldParentName);
            if (oldParent == null) {
                return null;
            }
            sourceList = oldParent.getNodes();
        }

        for (int i = 0; i < sourceList.size(); i++) {
            if (sourceList.get(i).getCategoryName().equals(categoryName)) {
                CategoryNode removed = sourceList.remove(i);
                return new RemovalResult<>(removed, i, sourceList);
            }
        }
        return null;
    }

    /**
     * Finds and removes a CashCategory from the tree structure.
     *
     * @return RemovalResult containing the removed category, its old index, and source list; or null if not found
     */
    private RemovalResult<CashCategory> findAndRemoveCategory(List<CashCategory> categories, CategoryName categoryName, CategoryName oldParentName) {
        List<CashCategory> sourceList;
        if (!oldParentName.isDefined()) {
            // Was at root level
            sourceList = categories;
        } else {
            // Was under a parent
            CashCategory oldParent = findCategoryByName(categories, oldParentName);
            if (oldParent == null) {
                return null;
            }
            sourceList = oldParent.getSubCategories();
        }

        for (int i = 0; i < sourceList.size(); i++) {
            if (sourceList.get(i).getCategoryName().equals(categoryName)) {
                CashCategory removed = sourceList.remove(i);
                return new RemovalResult<>(removed, i, sourceList);
            }
        }
        return null;
    }

    /**
     * Calculates adjusted position for same-list moves to handle off-by-one error.
     * When moving forward in the same list, the removal shifts indices down,
     * so we need to adjust the target position accordingly.
     */
    private <T> Integer calculateAdjustedPosition(Integer newPosition, int oldIndex, List<T> sourceList, List<T> targetList) {
        if (newPosition == null) {
            return null;
        }
        // Only adjust if source and target are the same list and moving forward
        if (sourceList == targetList && oldIndex < newPosition) {
            return newPosition - 1;
        }
        return newPosition;
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

    /**
     * Adds a CategoryNode to the list at the specified position.
     * If position is null or out of bounds, adds at the end.
     */
    private void addAtPosition(List<CategoryNode> list, CategoryNode node, Integer position) {
        if (position == null || position >= list.size()) {
            list.add(node);
        } else {
            list.add(Math.max(0, position), node);
        }
    }

    /**
     * Adds a CashCategory to the list at the specified position.
     * If position is null or out of bounds, adds at the end.
     */
    private void addCashCategoryAtPosition(List<CashCategory> list, CashCategory category, Integer position) {
        if (position == null || position >= list.size()) {
            list.add(category);
        } else {
            list.add(Math.max(0, position), category);
        }
    }
}
