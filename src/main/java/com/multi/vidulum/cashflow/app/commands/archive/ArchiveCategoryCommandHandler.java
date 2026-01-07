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
 * Handler for archiving a category in a CashFlow.
 * Archived categories are hidden from new transaction creation but remain visible in historical data.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ArchiveCategoryCommandHandler implements CommandHandler<ArchiveCategoryCommand, Void> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public Void handle(ArchiveCategoryCommand command) {
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

        // Check if it's a system category
        if (category.getOrigin() == CategoryOrigin.SYSTEM) {
            throw new CannotArchiveSystemCategoryException(command.categoryName());
        }

        CashFlowEvent.CategoryArchivedEvent event = new CashFlowEvent.CategoryArchivedEvent(
                command.cashFlowId(),
                command.categoryName(),
                command.categoryType(),
                ZonedDateTime.now(clock),
                command.forceArchiveChildren()
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.CategoryArchivedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Category [{}] of type [{}] archived in cashflow [{}]",
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
}
