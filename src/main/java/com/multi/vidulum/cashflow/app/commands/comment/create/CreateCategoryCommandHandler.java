package com.multi.vidulum.cashflow.app.commands.comment.create;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@AllArgsConstructor
public class CreateCategoryCommandHandler implements CommandHandler<CreateCategoryCommand, Void> {
    private DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;


    @Override
    public Void handle(CreateCategoryCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowEvent.CategoryCreatedEvent event = new CashFlowEvent.CategoryCreatedEvent(
                command.cashFlowId(),
                command.parentCategoryName(),
                command.categoryName(),
                command.type()
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
}
