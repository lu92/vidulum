package com.multi.vidulum.cashflow.app.commands.append;

import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CashFlowEventEmitter;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.OperationNotAllowedInSetupModeException;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.BusinessIdGenerator;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Appends many expected cash changes in one atomic operation: one aggregate load, one save, one
 * event, one reservation of ids.
 *
 * <p><b>Atomic means there is no partial result.</b> Every item is validated before a single one is
 * appended, so a batch either lands whole or changes nothing. That is the opposite of
 * {@code BatchDeleteExpectedCashChangesCommandHandler}, which skips the items it cannot delete and
 * counts them - and deliberately so: "already confirmed" is a normal outcome of tidying up after a
 * rule, while a due date outside the forecast window is a mistake by the caller, and silently
 * dropping it would leave a hole in someone's forecast with nothing saying so.
 *
 * <p>What it replaces for a caller like recurring-rules: one request per occurrence, each with its
 * own aggregate load, whole-document save, sequence round trip and topic message. A daily rule paid
 * 365 of each - and if the loop failed partway, the cash changes already written stayed behind while
 * the rule recorded none of their ids, so nothing could ever clean them up.
 */
@Slf4j
@Component
@AllArgsConstructor
public class BatchAppendExpectedCashChangesCommandHandler
        implements CommandHandler<BatchAppendExpectedCashChangesCommand, List<CashChangeId>> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final BusinessIdGenerator businessIdGenerator;
    private final Clock clock;

    @Override
    public List<CashChangeId> handle(BatchAppendExpectedCashChangesCommand command) {
        if (command.changes() == null || command.changes().isEmpty()) {
            log.info("Nothing to append for CashFlow [{}]", command.cashFlowId().id());
            return List.of();
        }

        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowNotFoundException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        if (CashFlow.CashFlowStatus.SETUP.equals(snapshot.status())) {
            throw new OperationNotAllowedInSetupModeException("batchAppendExpectedCashChanges", command.cashFlowId());
        }

        // Every item, before anything is appended. This loop is what makes the operation atomic:
        // nothing below runs unless all of them pass.
        command.changes().forEach(change ->
                ExpectedCashChangeRules.requireAppendable(snapshot, change.categoryName(), change.type(), change.dueDate()));

        // One reservation for the whole batch, not one per item
        List<CashChangeId> ids = businessIdGenerator.generateCashChangeIds(command.changes().size());

        ZonedDateTime created = ZonedDateTime.now(clock);

        List<CashFlowEvent.ExpectedCashChangesBatchAppendedEvent.AppendedExpectedCashChange> appended =
                new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            BatchAppendExpectedCashChangesCommand.ExpectedCashChangeToAppend change = command.changes().get(i);
            appended.add(new CashFlowEvent.ExpectedCashChangesBatchAppendedEvent.AppendedExpectedCashChange(
                    ids.get(i),
                    change.name(),
                    change.description(),
                    change.money(),
                    change.type(),
                    change.categoryName(),
                    change.dueDate(),
                    false
            ));
        }

        CashFlowEvent.ExpectedCashChangesBatchAppendedEvent event =
                new CashFlowEvent.ExpectedCashChangesBatchAppendedEvent(
                        command.cashFlowId(),
                        command.sourceRuleId(),
                        appended,
                        created);

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.ExpectedCashChangesBatchAppendedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Appended {} expected cash changes to CashFlow [{}] for sourceRuleId [{}]",
                appended.size(), command.cashFlowId().id(), command.sourceRuleId());

        return ids;
    }
}
