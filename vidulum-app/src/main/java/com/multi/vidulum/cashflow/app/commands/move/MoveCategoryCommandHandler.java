package com.multi.vidulum.cashflow.app.commands.move;

import com.multi.vidulum.cashflow.app.commands.archive.CategoryNotFoundException;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.CannotChangeCategoryTypeException;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Handler for moving a category to a different parent in a CashFlow.
 * <p>
 * Performs validation:
 * <ul>
 *   <li>Category must exist</li>
 *   <li>New parent must exist (if not moving to root)</li>
 *   <li>Cannot move system categories (e.g., "Uncategorized")</li>
 *   <li>Cannot create circular dependency (moving to own descendant)</li>
 *   <li>Cannot move to same parent (no-op)</li>
 * </ul>
 */
@Slf4j
@Component
@AllArgsConstructor
public class MoveCategoryCommandHandler implements CommandHandler<MoveCategoryCommand, Void> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public Void handle(MoveCategoryCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        List<Category> categories = command.categoryType() == Type.INFLOW
                ? cashFlow.getSnapshot().inflowCategories()
                : cashFlow.getSnapshot().outflowCategories();

        List<Category> oppositeCategories = command.categoryType() == Type.INFLOW
                ? cashFlow.getSnapshot().outflowCategories()
                : cashFlow.getSnapshot().inflowCategories();

        // 1. Find the category to move
        Category categoryToMove = findCategory(categories, command.categoryName());
        if (categoryToMove == null) {
            // Check if category exists in opposite type - if so, throw specific error
            Category categoryInOppositeType = findCategory(oppositeCategories, command.categoryName());
            if (categoryInOppositeType != null) {
                Type actualType = command.categoryType() == Type.INFLOW ? Type.OUTFLOW : Type.INFLOW;
                throw new CannotChangeCategoryTypeException(command.categoryName(), actualType, command.categoryType());
            }
            throw new CategoryNotFoundException(command.categoryName(), command.categoryType());
        }

        // 2. Check if it's a system category
        if (categoryToMove.getOrigin() == CategoryOrigin.SYSTEM) {
            throw new CannotMoveSystemCategoryException(command.categoryName());
        }

        // 3. Find current parent
        CategoryName currentParentName = findParentCategoryName(command.categoryName(), categories);

        // 4. Check if moving to same parent (no-op) - but allow if position is specified (reorder)
        if (currentParentName.equals(command.newParentCategoryName()) && command.position() == null) {
            throw new CategoryMoveToSameParentException(command.categoryName(), command.newParentCategoryName());
        }

        // 5. Validate new parent exists (if not moving to root)
        if (command.newParentCategoryName().isDefined()) {
            Category newParent = findCategory(categories, command.newParentCategoryName());
            if (newParent == null) {
                throw new CategoryNotFoundException(command.newParentCategoryName(), command.categoryType());
            }

            // 6. Check for circular dependency - cannot move to own descendant
            if (isDescendant(categoryToMove, command.newParentCategoryName())) {
                throw new CircularCategoryDependencyException(command.categoryName(), command.newParentCategoryName());
            }
        }

        // Create and apply event
        CashFlowEvent.CategoryMovedEvent event = new CashFlowEvent.CategoryMovedEvent(
                command.cashFlowId(),
                command.categoryName(),
                currentParentName,
                command.newParentCategoryName(),
                command.categoryType(),
                command.position(),
                ZonedDateTime.now(clock)
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.CategoryMovedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Category [{}] moved from [{}] to [{}] in cashflow [{}]",
                command.categoryName().name(),
                currentParentName.isDefined() ? currentParentName.name() : "root",
                command.newParentCategoryName().isDefined() ? command.newParentCategoryName().name() : "root",
                command.cashFlowId().id());

        return null;
    }

    private Category findCategory(List<Category> categories, CategoryName categoryName) {
        for (Category category : categories) {
            if (category.getCategoryName().equals(categoryName)) {
                return category;
            }
            Category found = findCategory(category.getSubCategories(), categoryName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Finds the parent category name of a given category.
     *
     * @return CategoryName of the parent, or NOT_DEFINED if at root level
     */
    private CategoryName findParentCategoryName(CategoryName target, List<Category> categories) {
        return findParentRecursive(target, categories, CategoryName.NOT_DEFINED);
    }

    private CategoryName findParentRecursive(CategoryName target, List<Category> categories, CategoryName currentParent) {
        for (Category category : categories) {
            // Check direct children first
            for (Category child : category.getSubCategories()) {
                if (child.getCategoryName().equals(target)) {
                    return category.getCategoryName();
                }
            }

            // Check if target is this category at current level
            if (category.getCategoryName().equals(target)) {
                return currentParent;
            }

            // Recurse into children - only continue if result is defined (found)
            CategoryName found = findParentRecursive(target, category.getSubCategories(), category.getCategoryName());
            if (found != null && found.isDefined()) {
                return found;
            }
        }
        return CategoryName.NOT_DEFINED;
    }

    /**
     * Checks if potentialDescendant is a descendant of ancestor.
     */
    private boolean isDescendant(Category ancestor, CategoryName potentialDescendant) {
        return findCategory(ancestor.getSubCategories(), potentialDescendant) != null;
    }
}
