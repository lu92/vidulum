package com.multi.vidulum.cashflow.app.commands.archive;

import com.multi.vidulum.cashflow.domain.*;
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
 * Handler for unarchiving a category in a CashFlow.
 * Unarchived categories become available again for new transaction creation.
 * <p>
 * <b>Important:</b> Unarchive is primarily intended for accidental archive recovery.
 * A category can only be unarchived if there is no other active (non-archived) category
 * with the same name. Once a new category with the same name is created, the old
 * archived category cannot be unarchived anymore - this prevents duplicate active categories.
 *
 * @see CannotUnarchiveCategoryException
 */
@Slf4j
@Component
@AllArgsConstructor
public class UnarchiveCategoryCommandHandler implements CommandHandler<UnarchiveCategoryCommand, Void> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public Void handle(UnarchiveCategoryCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        // Find the category
        List<Category> categories = command.categoryType() == Type.INFLOW
                ? cashFlow.getSnapshot().inflowCategories()
                : cashFlow.getSnapshot().outflowCategories();

        Category category = findCategory(categories, command.categoryName());
        if (category == null) {
            throw new CategoryNotFoundException(command.categoryName(), command.categoryType());
        }

        // Check if there's another ACTIVE category with the same name
        // Only allow unarchive if no active category with same name exists
        boolean hasActiveCategoryWithSameName = hasActiveCategory(categories, command.categoryName());
        if (hasActiveCategoryWithSameName) {
            throw new CannotUnarchiveCategoryException(command.categoryName());
        }

        CashFlowEvent.CategoryUnarchivedEvent event = new CashFlowEvent.CategoryUnarchivedEvent(
                command.cashFlowId(),
                command.categoryName(),
                command.categoryType(),
                ZonedDateTime.now(clock)
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.CategoryUnarchivedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Category [{}] of type [{}] unarchived in cashflow [{}]",
                command.categoryName().name(), command.categoryType(), command.cashFlowId().id());
        return null;
    }

    private Category findCategory(List<Category> categories, CategoryName categoryName) {
        for (Category category : categories) {
            if (category.getCategoryName().equals(categoryName)) {
                return category;
            }
            // Check subcategories
            Category found = findCategory(category.getSubCategories(), categoryName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Checks if there's an active (non-archived) category with the given name.
     * This searches through all categories including subcategories.
     */
    private boolean hasActiveCategory(List<Category> categories, CategoryName categoryName) {
        for (Category category : categories) {
            if (category.getCategoryName().equals(categoryName) && category.isActive()) {
                return true;
            }
            // Check subcategories
            if (hasActiveCategory(category.getSubCategories(), categoryName)) {
                return true;
            }
        }
        return false;
    }
}
