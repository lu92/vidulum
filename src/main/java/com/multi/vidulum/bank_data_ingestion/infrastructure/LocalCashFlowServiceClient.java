package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.cashflow.app.commands.comment.create.CreateCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.importhistorical.ImportHistoricalCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.rollbackimport.RollbackImportCommand;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local (in-process) implementation of CashFlowServiceClient.
 *
 * This implementation directly uses repositories and CommandGateway to communicate
 * with the CashFlow domain within the same JVM process.
 *
 * In a microservice architecture, this would be replaced by HttpCashFlowServiceClient
 * that makes HTTP calls to a separate cashflow-service.
 */
@Slf4j
@Component
public class LocalCashFlowServiceClient implements CashFlowServiceClient {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CommandGateway commandGateway;

    public LocalCashFlowServiceClient(
            DomainCashFlowRepository domainCashFlowRepository,
            @Lazy CommandGateway commandGateway) {
        this.domainCashFlowRepository = domainCashFlowRepository;
        this.commandGateway = commandGateway;
    }

    @Override
    public CashFlowInfo getCashFlowInfo(String cashFlowId) {
        CashFlow cashFlow = domainCashFlowRepository.findById(new CashFlowId(cashFlowId))
                .orElseThrow(() -> new CashFlowNotFoundException(cashFlowId));

        return mapToInfo(cashFlow.getSnapshot());
    }

    @Override
    public boolean exists(String cashFlowId) {
        return domainCashFlowRepository.findById(new CashFlowId(cashFlowId)).isPresent();
    }

    @Override
    public void createCategory(String cashFlowId, String categoryName, String parentCategoryName, Type type) {
        try {
            CreateCategoryCommand command = new CreateCategoryCommand(
                    new CashFlowId(cashFlowId),
                    parentCategoryName != null ? new CategoryName(parentCategoryName) : CategoryName.NOT_DEFINED,
                    new CategoryName(categoryName),
                    type
            );

            commandGateway.send(command);

            log.debug("Created category [{}] in CashFlow [{}]", categoryName, cashFlowId);

        } catch (com.multi.vidulum.cashflow.domain.CategoryAlreadyExistsException e) {
            throw new CategoryAlreadyExistsException(categoryName);
        }
    }

    @Override
    public String importHistoricalTransaction(String cashFlowId, ImportTransactionRequest request) {
        try {
            ImportHistoricalCashChangeCommand command = new ImportHistoricalCashChangeCommand(
                    new CashFlowId(cashFlowId),
                    new CategoryName(request.categoryName()),
                    new Name(request.name()),
                    new Description(request.description()),
                    Money.of(request.amount(), request.currency()),
                    request.type(),
                    request.dueDate().atStartOfDay(java.time.ZoneId.systemDefault()),
                    request.paidDate().atStartOfDay(java.time.ZoneId.systemDefault())
            );

            CashChangeId cashChangeId = commandGateway.send(command);

            log.debug("Imported transaction [{}] into CashFlow [{}]", request.name(), cashFlowId);

            return cashChangeId.id();

        } catch (Exception e) {
            throw new ImportFailedException(e.getMessage(), e);
        }
    }

    @Override
    public RollbackResult rollbackImport(String cashFlowId, boolean deleteCategories) {
        // Get state before rollback
        CashFlowInfo infoBefore = getCashFlowInfo(cashFlowId);
        int transactionsBefore = infoBefore.cashChangesCount();
        int categoriesBefore = infoBefore.countCategories();

        // Execute rollback
        RollbackImportCommand command = new RollbackImportCommand(
                new CashFlowId(cashFlowId),
                deleteCategories
        );

        CashFlowSnapshot snapshotAfter = commandGateway.send(command);
        CashFlowInfo infoAfter = mapToInfo(snapshotAfter);

        // Calculate what was deleted
        int transactionsDeleted = transactionsBefore - infoAfter.cashChangesCount();
        int categoriesDeleted = deleteCategories ? (categoriesBefore - infoAfter.countCategories()) : 0;

        log.info("Rolled back import for CashFlow [{}]. Deleted {} transactions, {} categories",
                cashFlowId, transactionsDeleted, categoriesDeleted);

        return new RollbackResult(transactionsDeleted, categoriesDeleted, infoAfter);
    }

    // ============ Mapping Methods ============

    private CashFlowInfo mapToInfo(CashFlowSnapshot snapshot) {
        return new CashFlowInfo(
                snapshot.cashFlowId().id(),
                mapStatus(snapshot.status()),
                snapshot.activePeriod(),
                snapshot.startPeriod(),
                mapCategories(snapshot.inflowCategories(), Type.INFLOW),
                mapCategories(snapshot.outflowCategories(), Type.OUTFLOW),
                extractTransactionIds(snapshot),
                snapshot.cashChanges().size()
        );
    }

    private CashFlowInfo.CashFlowStatus mapStatus(CashFlow.CashFlowStatus status) {
        return switch (status) {
            case SETUP -> CashFlowInfo.CashFlowStatus.SETUP;
            case OPEN -> CashFlowInfo.CashFlowStatus.OPEN;
            case CLOSED -> CashFlowInfo.CashFlowStatus.CLOSED;
        };
    }

    private List<CashFlowInfo.CategoryInfo> mapCategories(List<Category> categories, Type type) {
        return categories.stream()
                .map(cat -> mapCategory(cat, null, type))
                .toList();
    }

    private CashFlowInfo.CategoryInfo mapCategory(Category category, String parentName, Type type) {
        return new CashFlowInfo.CategoryInfo(
                category.getCategoryName().name(),
                parentName,
                type,
                category.isArchived(),
                category.getSubCategories().stream()
                        .map(sub -> mapCategory(sub, category.getCategoryName().name(), type))
                        .toList()
        );
    }

    private Set<String> extractTransactionIds(CashFlowSnapshot snapshot) {
        // In the future, we might store bankTransactionId in CashChange
        // For now, return empty set
        return new HashSet<>();
    }
}
