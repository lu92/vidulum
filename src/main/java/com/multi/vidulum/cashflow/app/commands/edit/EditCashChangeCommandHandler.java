package com.multi.vidulum.cashflow.app.commands.edit;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
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

@Slf4j
@Component
@AllArgsConstructor
public class EditCashChangeCommandHandler implements CommandHandler<EditCashChangeCommand, Void> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public Void handle(EditCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        // Validate: operation not allowed in SETUP mode
        if (CashFlow.CashFlowStatus.SETUP.equals(snapshot.status())) {
            throw new OperationNotAllowedInSetupModeException("editCashChange", command.cashFlowId());
        }

        // Find the CashChange to get its type
        CashChangeSnapshot cashChange = snapshot.cashChanges().get(command.cashChangeId());
        if (cashChange == null) {
            throw new CashChangeDoesNotExistsException(command.cashChangeId());
        }

        // Get categories based on the transaction type (INFLOW or OUTFLOW)
        List<Category> categories = cashChange.type() == Type.INFLOW
                ? snapshot.inflowCategories()
                : snapshot.outflowCategories();

        // Validate: category must exist and be active (not archived)
        Category activeCategory = findActiveCategory(categories, command.categoryName());
        if (activeCategory == null) {
            // No active category found - check if there's an archived one
            Category archivedCategory = findArchivedCategory(categories, command.categoryName());
            if (archivedCategory != null) {
                throw new CategoryIsArchivedException(command.categoryName());
            }
            // No category at all
            throw new CategoryDoesNotExistsException(command.categoryName());
        }

        CashFlowEvent.CashChangeEditedEvent event = new CashFlowEvent.CashChangeEditedEvent(
                command.cashFlowId(),
                command.cashChangeId(),
                command.name(),
                command.description(),
                command.money(),
                command.categoryName(),
                command.dueDate(),
                ZonedDateTime.now(clock)
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.CashChangeEditedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );
        log.info("Cash change [{}] has been edited!", command.cashChangeId());
        return null;
    }

    /**
     * Finds an active (non-archived) category by name.
     */
    private Category findActiveCategory(List<Category> categories, CategoryName categoryName) {
        for (Category category : categories) {
            if (category.getCategoryName().equals(categoryName) && category.isActive()) {
                return category;
            }
            // Check subcategories
            Category found = findActiveCategory(category.getSubCategories(), categoryName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Finds an archived category by name.
     */
    private Category findArchivedCategory(List<Category> categories, CategoryName categoryName) {
        for (Category category : categories) {
            if (category.getCategoryName().equals(categoryName) && category.isArchived()) {
                return category;
            }
            // Check subcategories
            Category found = findArchivedCategory(category.getSubCategories(), categoryName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
