package com.multi.vidulum.cashflow.app.commands.importhistorical;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.BusinessIdGenerator;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Map;

@Slf4j
@Component
@AllArgsConstructor
public class ImportHistoricalCashChangeCommandHandler implements CommandHandler<ImportHistoricalCashChangeCommand, CashChangeId> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final BusinessIdGenerator businessIdGenerator;
    private final Clock clock;

    @Override
    public CashChangeId handle(ImportHistoricalCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowNotFoundException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();
        ZonedDateTime now = ZonedDateTime.now(clock);

        // One rule, one implementation. This used to be a switch over the status plus two
        // if-statements here, and the same five conditions again as String.format messages in
        // StageTransactionsCommandHandler - which had already drifted apart. ImportWindow lives in
        // the contract so staging can evaluate it against a skeleton it fetched once, instead of
        // keeping a copy; the order of the checks inside it is this handler's former order exactly,
        // because which refusal surfaces first is observable in the error code an endpoint answers.
        snapshot.importWindow()
                .refusalFor(command.paidDate(), now)
                .ifPresent(refusal -> {
                    throw refusal;
                });

        CashChangeId cashChangeId = businessIdGenerator.generateCashChangeId();

        CashFlowEvent.HistoricalCashChangeImportedEvent event = new CashFlowEvent.HistoricalCashChangeImportedEvent(
                command.cashFlowId(),
                cashChangeId,
                command.name(),
                command.description(),
                command.money(),
                command.type(),
                command.categoryName(),
                command.dueDate(),
                command.paidDate(),
                now,
                command.selfTransfer()
        );

        cashFlow.apply(event);
        domainCashFlowRepository.save(cashFlow);

        log.info("Historical cash change [{}] imported to CashFlow [{}] for period [{}]",
                cashChangeId.id(), command.cashFlowId().id(), YearMonth.from(command.paidDate()));

        // Use emitWithKey to ensure event ordering within the same CashFlow
        // Critical for import: CategoryCreatedEvent must be processed before HistoricalCashChangeImportedEvent
        cashFlowEventEmitter.emitWithKey(
                command.cashFlowId(),
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.HistoricalCashChangeImportedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        return cashChangeId;
    }
}
