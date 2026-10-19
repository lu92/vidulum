package com.multi.vidulum.cashflow.app.commands.append;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.CategoryName;
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
                .orElseThrow(() -> new CashFlowNotFoundException(command.cashFlowId()));

        // Validate: operation not allowed in SETUP mode
        if (CashFlow.CashFlowStatus.SETUP.equals(cashFlow.getSnapshot().status())) {
            throw new OperationNotAllowedInSetupModeException("appendExpectedCashChange", command.cashFlowId());
        }

        // The same two checks the batch handler makes, in the same order - see
        // ExpectedCashChangeRules. They were private methods here until the batch command needed
        // them too, and two copies of "may this be appended" is the pair that drifts.
        ExpectedCashChangeRules.requireAppendable(
                cashFlow.getSnapshot(), command.categoryName(), command.type(), command.dueDate());

        CashFlowEvent.ExpectedCashChangeAppendedEvent event = new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                command.cashFlowId(),
                command.cashChangeId(),
                command.name(),
                command.description(),
                command.money(),
                command.type(),
                command.created(),
                command.categoryName(),
                command.dueDate(),
                command.sourceRuleId(),
                false
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
}
