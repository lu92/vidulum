package com.multi.vidulum.cashflow.app.commands.rollbackimport;

import com.multi.vidulum.cashflow.domain.*;
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
import java.util.Stack;

@Slf4j
@Component
@AllArgsConstructor
public class RollbackImportCommandHandler implements CommandHandler<RollbackImportCommand, CashFlowSnapshot> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public CashFlowSnapshot handle(RollbackImportCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        // Validation: CashFlow must be in SETUP mode
        if (snapshot.status() != CashFlow.CashFlowStatus.SETUP) {
            throw new RollbackNotAllowedInNonSetupModeException(command.cashFlowId(), snapshot.status());
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        // Count transactions to be deleted
        int transactionsCount = snapshot.cashChanges().size();

        // Count custom categories (excluding Uncategorized) if they will be deleted
        int categoriesCount = 0;
        if (command.deleteCategories()) {
            categoriesCount = countCustomCategories(snapshot.inflowCategories())
                    + countCustomCategories(snapshot.outflowCategories());
        }

        CashFlowEvent.ImportRolledBackEvent event = new CashFlowEvent.ImportRolledBackEvent(
                command.cashFlowId(),
                transactionsCount,
                categoriesCount,
                command.deleteCategories(),
                now
        );

        cashFlow.apply(event);
        domainCashFlowRepository.save(cashFlow);

        log.info("Import rolled back for CashFlow [{}]. Deleted [{}] transactions, [{}] categories (categoriesDeleted: [{}])",
                command.cashFlowId().id(), transactionsCount, categoriesCount, command.deleteCategories());

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.ImportRolledBackEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        return cashFlow.getSnapshot();
    }

    /**
     * Count custom categories (all categories except Uncategorized).
     */
    private int countCustomCategories(List<Category> categories) {
        int count = 0;
        Stack<Category> stack = new Stack<>();
        categories.forEach(stack::push);
        while (!stack.isEmpty()) {
            Category category = stack.pop();
            // Count all categories except Uncategorized
            if (!category.getCategoryName().name().equals("Uncategorized")) {
                count++;
            }
            category.getSubCategories().forEach(stack::push);
        }
        return count;
    }
}
