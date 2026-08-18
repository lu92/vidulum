package com.multi.vidulum.cashflow.app.commands.budgeting.update;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Map;

@Slf4j
@Component
@AllArgsConstructor
public class UpdateBudgetingCommandHandler implements CommandHandler<UpdateBudgetingCommand, Void> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public Void handle(UpdateBudgetingCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowEvent.BudgetingUpdatedEvent event = new CashFlowEvent.BudgetingUpdatedEvent(
                command.cashFlowId(),
                command.categoryName(),
                command.categoryType(),
                command.newBudget(),
                ZonedDateTime.now(clock)
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.BudgetingUpdatedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Budgeting updated for category [{}] in cashflow [{}]", command.categoryName().name(), command.cashFlowId().id());
        return null;
    }
}
