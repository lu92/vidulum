package com.multi.vidulum.cashflow.app.commands.append;

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

@Slf4j
@Component
@AllArgsConstructor
public class AppendPaidCashChangeCommandHandler implements CommandHandler<AppendPaidCashChangeCommand, CashChangeId> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public CashChangeId handle(AppendPaidCashChangeCommand command) {
        // Validate: paidDate cannot be in the future
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (command.paidDate().isAfter(now)) {
            throw new PaidDateInFutureException(command.paidDate(), now);
        }

        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        // Validate: operation not allowed in SETUP mode
        if (CashFlow.CashFlowStatus.SETUP.equals(cashFlow.getSnapshot().status())) {
            throw new OperationNotAllowedInSetupModeException("appendPaidCashChange", command.cashFlowId());
        }

        // Validate: cannot add cash change to archived category
        // When multiple categories with the same name exist (one archived, one active),
        // we need to check if there's an active one available
        List<Category> categories = command.type() == Type.INFLOW
                ? cashFlow.getSnapshot().inflowCategories()
                : cashFlow.getSnapshot().outflowCategories();
        Category activeCategory = findActiveCategory(categories, command.categoryName());
        if (activeCategory == null) {
            // No active category found - check if there's an archived one
            Category archivedCategory = findArchivedCategory(categories, command.categoryName());
            if (archivedCategory != null) {
                throw new CategoryIsArchivedException(command.categoryName());
            }
            // No category at all - will be handled by domain layer
        }

        CashFlowEvent.PaidCashChangeAppendedEvent event = new CashFlowEvent.PaidCashChangeAppendedEvent(
                command.cashFlowId(),
                command.cashChangeId(),
                command.name(),
                command.description(),
                command.money(),
                command.type(),
                command.created(),
                command.categoryName(),
                command.dueDate(),
                command.paidDate()
        );
        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.PaidCashChangeAppendedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Paid cash change [{}] has been appended!", cashFlow.getSnapshot());
        return command.cashChangeId();
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
