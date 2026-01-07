package com.multi.vidulum.cashflow.app.commands.comment.create;

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
 * Handler for creating a new category in a CashFlow.
 * <p>
 * <b>Category naming rules:</b>
 * <ul>
 *   <li>Only one active (non-archived) category with a given name can exist at a time</li>
 *   <li>Creating a category with the same name as an archived category is allowed</li>
 *   <li>This enables category "versioning" - old archived category remains for historical data,
 *       new category is used for new transactions</li>
 * </ul>
 *
 * @see CategoryAlreadyExistsException
 */
@Slf4j
@Component
@AllArgsConstructor
public class CreateCategoryCommandHandler implements CommandHandler<CreateCategoryCommand, Void> {
    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public Void handle(CreateCategoryCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        // Check if there's already an active category with the same name
        List<Category> categories = Type.INFLOW.equals(command.type())
                ? cashFlow.getSnapshot().inflowCategories()
                : cashFlow.getSnapshot().outflowCategories();

        if (hasActiveCategory(categories, command.categoryName())) {
            throw new CategoryAlreadyExistsException(command.categoryName());
        }

        CashFlowEvent.CategoryCreatedEvent event = new CashFlowEvent.CategoryCreatedEvent(
                command.cashFlowId(),
                command.parentCategoryName(),
                command.categoryName(),
                command.type(),
                ZonedDateTime.now(clock)
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.CategoryCreatedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );
        log.info("New category [{}] has been added to cash flow [{}]", command.categoryName(), command.cashFlowId());
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
