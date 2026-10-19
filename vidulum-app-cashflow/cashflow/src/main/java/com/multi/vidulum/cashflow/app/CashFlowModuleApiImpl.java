package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.CategoryNodeView;
import com.multi.vidulum.cashflow.api.view.ImportedTransaction;
import com.multi.vidulum.cashflow.api.view.ImportedTransactions;
import com.multi.vidulum.cashflow.api.HistoricalCashChange;
import com.multi.vidulum.cashflow.api.RollbackResult;
import com.multi.vidulum.cashflow.app.commands.comment.create.CreateCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.importhistorical.ImportHistoricalCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.rollbackimport.RollbackImportCommand;
import com.multi.vidulum.cashflow.app.commands.rollbackimport.RollbackImportResult;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.api.DeletedExpectedCashChanges;
import com.multi.vidulum.cashflow.api.ExpectedCashChangesByStatus;
import com.multi.vidulum.cashflow.api.ExpectedCashChange;
import com.multi.vidulum.cashflow.app.commands.append.BatchAppendExpectedCashChangesCommand;
import com.multi.vidulum.cashflow.app.commands.delete.BatchDeleteExpectedCashChangesCommand;
import com.multi.vidulum.cashflow.app.commands.delete.BatchDeleteResult;
import com.multi.vidulum.cashflow.domain.CashChangeStatus;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cashflow answering its own contract, in this process.
 *
 * <p>Package-private: nobody outside this module names this class, they inject
 * {@link CashFlowModuleApi}. That is also what {@code ModuleBoundariesArchTest} requires of every
 * contract implementation.
 *
 * <p><b>Reads through the repository, writes through the gateway</b>, and the split is not
 * stylistic. The reads are projections of the aggregate's own state with no query handler behind
 * them, so going through the query gateway would add a hop and nothing else. The write is a
 * decision - which the aggregate makes, by checking the lifecycle phase and the existing names -
 * so it goes through {@code CreateCategoryCommand} and the handler that already enforces it.
 *
 * <p>This is <b>the default implementation and the one every test exercises</b>, selected by
 * {@code vidulum.cashflow.api.transport=in-process} (the value you get by not setting it).
 * {@code HttpCashFlowModuleApi} answers the same contract over REST for the day
 * bank-data-ingestion runs in another process; the two are mutually exclusive, and
 * {@code BootstrapWiresEveryModuleTest} asserts only one is ever a bean.
 */
@Component
@ConditionalOnProperty(name = "vidulum.cashflow.api.transport", havingValue = "in-process", matchIfMissing = true)
@AllArgsConstructor
class CashFlowModuleApiImpl implements CashFlowModuleApi {

    private final DomainCashFlowRepository repository;
    private final CommandGateway commandGateway;

    @Override
    public CashFlowSkeletonView skeletonOf(CashFlowId cashFlowId) {
        CashFlow cashFlow = repository.findById(cashFlowId)
                .orElseThrow(() -> new CashFlowNotFoundException(cashFlowId));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        return new CashFlowSkeletonView(
                snapshot.cashFlowId(),
                snapshot.userId(),
                snapshot.phase(),
                snapshot.activePeriod(),
                snapshot.startPeriod(),
                nodesOf(snapshot.inflowCategories(), null),
                nodesOf(snapshot.outflowCategories(), null));
    }

    @Override
    public boolean exists(CashFlowId cashFlowId) {
        // Loads the aggregate to answer a boolean, because DomainCashFlowRepository has no
        // existsById. Still one read against Mongo where the callers used to pay two HTTP requests
        // and a full payload; a narrower repository method is worth adding when something needs it
        // in a loop.
        return repository.findById(cashFlowId).isPresent();
    }

    @Override
    public void createCategory(CashFlowId cashFlowId,
                               CategoryName categoryName,
                               CategoryName parentCategoryName,
                               Type type,
                               boolean isImport) {
        // Through the gateway rather than the repository: creating a category is a decision the
        // aggregate makes - it checks the lifecycle phase, refuses a name an active category holds,
        // and emits CategoryCreatedEvent. Reaching for the repository here would reimplement all
        // three. Injecting CommandGateway costs nothing and closes no cycle, because since step 08
        // the gateway is built with no dependencies and handlers register themselves afterwards;
        // CqrsGatewayConfigurationTest is what keeps that true.
        //
        // The null is normalised here, and that is not cosmetic. The domain spells "no parent" as
        // CategoryName.NOT_DEFINED, and CashFlowForecastRestController's path has always converted
        // for it; this implementation did not, so a top-level category created through the contract
        // emitted CategoryCreatedEvent with a null parent. The aggregate accepted it - but
        // CategoryCreatedEventHandler does parentCategoryName().equals(NOT_DEFINED), so the forecast
        // projection died with an NPE, retried nine times and dropped the event. Nothing went red,
        // because no test read the forecast after a category created this way. Step 28 made staging
        // read it, which is how this surfaced.
        commandGateway.send(new CreateCategoryCommand(
                cashFlowId,
                parentCategoryName == null ? CategoryName.NOT_DEFINED : parentCategoryName,
                categoryName,
                type,
                isImport));
    }

    @Override
    public CashChangeId importHistorical(CashFlowId cashFlowId, HistoricalCashChange change) {
        // Through the gateway for the same reason createCategory is: the aggregate decides whether
        // the date is importable at all, and the handler already enforces that through ImportWindow.
        // Name and Description are wrapped here, on cashflow's side - the contract carries strings
        // because those two value objects live in cashflow.domain, where a consumer may not look.
        return commandGateway.send(new ImportHistoricalCashChangeCommand(
                cashFlowId,
                change.categoryName(),
                new Name(change.name()),
                new Description(change.description()),
                change.money(),
                change.type(),
                change.dueDate(),
                change.paidDate(),
                change.selfTransfer(),
                change.bankTransactionId()));
    }

    @Override
    public List<CashChangeId> appendExpected(CashFlowId cashFlowId, String sourceRuleId, List<ExpectedCashChange> changes) {
        // Through the gateway, like every other write here: the aggregate decides whether each item
        // may be appended at all, and the handler reserves the ids for the whole batch in one go.
        // Name and Description are wrapped on this side - the contract carries strings because those
        // two value objects live in cashflow.domain, where a consumer may not look.
        return commandGateway.send(new BatchAppendExpectedCashChangesCommand(
                cashFlowId,
                sourceRuleId,
                changes.stream()
                        .map(change -> new BatchAppendExpectedCashChangesCommand.ExpectedCashChangeToAppend(
                                change.categoryName(),
                                new Name(change.name()),
                                new Description(change.description()),
                                change.money(),
                                change.type(),
                                change.dueDate()))
                        .toList()));
    }

    @Override
    public ExpectedCashChangesByStatus expectedCashChangesOf(CashFlowId cashFlowId, String sourceRuleId) {
        CashFlow cashFlow = repository.findById(cashFlowId)
                .orElseThrow(() -> new CashFlowNotFoundException(cashFlowId));

        // Through the repository rather than the gateway: this reads, and the aggregate decides
        // nothing - the same reason skeletonOf and alreadyImported go this way. The counting itself
        // is the aggregate's, because the endpoint needs the same answer.
        CashFlow.StatusCounts counts = cashFlow.countBySourceRule(sourceRuleId);

        return new ExpectedCashChangesByStatus(counts.pending(), counts.confirmed(), counts.other());
    }

    @Override
    public DeletedExpectedCashChanges deleteExpected(CashFlowId cashFlowId, String sourceRuleId, List<CashChangeId> cashChangeIds) {
        // The command already existed, with its own batch event and a projection handler for it -
        // only the contract was missing, which is why this method is three lines while appendExpected
        // needed a command, an event and an apply of its own.
        BatchDeleteResult result = commandGateway.send(new BatchDeleteExpectedCashChangesCommand(
                cashFlowId, sourceRuleId, cashChangeIds));

        return new DeletedExpectedCashChanges(result.deletedCount(), result.skippedCount());
    }

    @Override
    public RollbackResult rollbackImport(CashFlowId cashFlowId, boolean deleteCategories) {
        RollbackImportResult result = commandGateway.send(
                new RollbackImportCommand(cashFlowId, deleteCategories));

        return new RollbackResult(result.transactionsDeleted(), result.categoriesDeleted());
    }

    @Override
    public ImportedTransactions alreadyImported(CashFlowId cashFlowId) {
        CashFlow cashFlow = repository.findById(cashFlowId)
                .orElseThrow(() -> new CashFlowNotFoundException(cashFlowId));

        return new ImportedTransactions(cashFlow.getSnapshot().cashChanges().values().stream()
                .filter(cashChange -> cashChange.bankTransactionId() != null)
                .collect(Collectors.toMap(
                        CashChangeSnapshot::bankTransactionId,
                        CashFlowModuleApiImpl::importedFrom,
                        // A unique index cannot enforce this - cash changes are an embedded array -
                        // so two entries for one source id are possible if something wrote around
                        // CashFlow.findImported. Keeping the first is arbitrary; failing here would
                        // turn a reporting call into an outage, so it keeps one and says nothing.
                        (first, second) -> first)));
    }

    /**
     * The paid date comes out of {@code endDate}, because that is where the aggregate puts it -
     * {@code CashChangeSnapshot} has no {@code paidDate} field of its own.
     */
    private static ImportedTransaction importedFrom(CashChangeSnapshot cashChange) {
        return new ImportedTransaction(
                cashChange.bankTransactionId(),
                cashChange.cashChangeId(),
                cashChange.endDate(),
                cashChange.money(),
                cashChange.categoryName());
    }

    /**
     * The category tree, carrying each node's parent down with it - the one piece of redundancy the
     * view keeps, and the reason it keeps it is the flat lookups in categorisation.
     */
    private static List<CategoryNodeView> nodesOf(List<Category> categories, CategoryName parentName) {
        if (categories == null) {
            return List.of();
        }
        return categories.stream()
                .map(category -> new CategoryNodeView(
                        category.getCategoryName(),
                        parentName,
                        category.isArchived(),
                        nodesOf(category.getSubCategories(), category.getCategoryName())))
                .toList();
    }
}
