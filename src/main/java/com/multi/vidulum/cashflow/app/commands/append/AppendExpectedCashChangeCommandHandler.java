package com.multi.vidulum.cashflow.app.commands.append;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@AllArgsConstructor
public class AppendExpectedCashChangeCommandHandler implements CommandHandler<AppendExpectedCashChangeCommand, CashChangeId> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;

    @Override
    public CashChangeId handle(AppendExpectedCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        // Validate: operation not allowed in SETUP mode
        if (CashFlow.CashFlowStatus.SETUP.equals(cashFlow.getSnapshot().status())) {
            throw new OperationNotAllowedInSetupModeException("appendExpectedCashChange", command.cashFlowId());
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

        // Validate: dueDate must be within allowed range (activePeriod to activePeriod + 11 months)
        validateDueDateRange(command.dueDate(), cashFlow.getSnapshot().activePeriod());

        CashFlowEvent.ExpectedCashChangeAppendedEvent event = new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                command.cashFlowId(),
                command.cashChangeId(),
                command.name(),
                command.description(),
                command.money(),
                command.type(),
                command.created(),
                command.categoryName(),
                command.dueDate()
        );
        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Expected cash change [{}] has been appended!", cashFlow.getSnapshot());
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

    /**
     * Validates that dueDate is within allowed range.
     * Allowed range: activePeriod (current month) to activePeriod + 11 months (forecasted period).
     *
     * @param dueDate      the due date to validate
     * @param activePeriod the current active period
     * @throws DueDateOutsideAllowedRangeException if dueDate is outside allowed range
     */
    private void validateDueDateRange(ZonedDateTime dueDate, YearMonth activePeriod) {
        YearMonth dueDateMonth = YearMonth.from(dueDate);
        YearMonth maxAllowedMonth = activePeriod.plusMonths(11);

        if (dueDateMonth.isBefore(activePeriod) || dueDateMonth.isAfter(maxAllowedMonth)) {
            throw new DueDateOutsideAllowedRangeException(dueDate, activePeriod);
        }
    }
}
