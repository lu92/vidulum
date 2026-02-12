package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.cashflow.app.commands.comment.create.CreateCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.importhistorical.ImportHistoricalCashChangeCommand;
import com.multi.vidulum.cashflow.app.queries.GetCashFlowQuery;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * Test implementation of CashFlowServiceClient that uses QueryGateway and CommandGateway.
 * Used in integration tests to avoid HTTP communication overhead and circular dependencies.
 * Uses @Lazy on CommandGateway to break the circular dependency:
 * CommandGateway -> ConfigureCategoryMappingCommandHandler -> CashFlowServiceClient -> CommandGateway
 */
@Slf4j
public class TestCashFlowServiceClient implements CashFlowServiceClient {

    private final QueryGateway queryGateway;
    private final CommandGateway commandGateway;

    public TestCashFlowServiceClient(QueryGateway queryGateway, @Lazy CommandGateway commandGateway) {
        this.queryGateway = queryGateway;
        this.commandGateway = commandGateway;
    }

    @Override
    public CashFlowInfo getCashFlowInfo(String cashFlowId) {
        CashFlowSnapshot snapshot = queryGateway.send(new GetCashFlowQuery(new CashFlowId(cashFlowId)));
        return mapToCashFlowInfo(snapshot);
    }

    @Override
    public boolean exists(String cashFlowId) {
        try {
            queryGateway.send(new GetCashFlowQuery(new CashFlowId(cashFlowId)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void createCategory(String cashFlowId, String categoryName, String parentCategoryName, Type type) {
        // Use forImport to allow category creation in SETUP mode (during import)
        commandGateway.send(CreateCategoryCommand.forImport(
                new CashFlowId(cashFlowId),
                parentCategoryName != null ? new CategoryName(parentCategoryName) : null,
                new CategoryName(categoryName),
                type
        ));
        log.debug("Created category {} (parent: {}) of type {} for CashFlow {}",
                categoryName, parentCategoryName, type, cashFlowId);
    }

    @Override
    public String importHistoricalTransaction(String cashFlowId, ImportTransactionRequest request) {
        CashChangeId cashChangeId = commandGateway.send(
                new ImportHistoricalCashChangeCommand(
                        new CashFlowId(cashFlowId),
                        new CategoryName(request.categoryName()),
                        new Name(request.name()),
                        new Description(request.description()),
                        Money.of(request.amount(), request.currency()),
                        request.type(),
                        request.dueDate().atStartOfDay(ZoneId.systemDefault()),
                        request.paidDate().atStartOfDay(ZoneId.systemDefault())
                ));
        log.debug("Imported historical transaction {} for CashFlow {}", cashChangeId.id(), cashFlowId);
        return cashChangeId.id();
    }

    @Override
    public RollbackResult rollbackImport(String cashFlowId, boolean deleteCategories) {
        // For test purposes, we get the CashFlow info
        CashFlowSnapshot snapshot = queryGateway.send(new GetCashFlowQuery(new CashFlowId(cashFlowId)));
        int transactionsCount = snapshot.cashChanges().size();
        CashFlowInfo infoAfter = mapToCashFlowInfo(snapshot);

        return new RollbackResult(transactionsCount, deleteCategories ? 0 : 0, infoAfter);
    }

    private CashFlowInfo mapToCashFlowInfo(CashFlowSnapshot snapshot) {
        List<CashFlowInfo.CategoryInfo> inflowCategories = mapCategories(snapshot.inflowCategories(), Type.INFLOW);
        List<CashFlowInfo.CategoryInfo> outflowCategories = mapCategories(snapshot.outflowCategories(), Type.OUTFLOW);

        Set<String> existingTransactionIds = new HashSet<>();
        snapshot.cashChanges().forEach((id, cashChange) -> existingTransactionIds.add(id.id()));

        CashFlowInfo.CashFlowStatus status = switch (snapshot.status()) {
            case SETUP -> CashFlowInfo.CashFlowStatus.SETUP;
            case OPEN -> CashFlowInfo.CashFlowStatus.OPEN;
            case CLOSED -> CashFlowInfo.CashFlowStatus.CLOSED;
        };

        // Build month statuses (simplified - for tests we generate based on periods)
        Map<YearMonth, CashFlowInfo.MonthStatus> monthStatuses = generateMonthStatuses(snapshot);

        return new CashFlowInfo(
                snapshot.cashFlowId().id(),
                status,
                snapshot.activePeriod(),
                snapshot.startPeriod(),
                inflowCategories,
                outflowCategories,
                existingTransactionIds,
                snapshot.cashChanges().size(),
                monthStatuses
        );
    }

    /**
     * Generate month statuses based on CashFlow snapshot.
     * This is a simplified version - in production, month statuses come from forecast processor.
     */
    private Map<YearMonth, CashFlowInfo.MonthStatus> generateMonthStatuses(CashFlowSnapshot snapshot) {
        Map<YearMonth, CashFlowInfo.MonthStatus> statuses = new HashMap<>();

        YearMonth startPeriod = snapshot.startPeriod();
        YearMonth activePeriod = snapshot.activePeriod();

        if (startPeriod == null) {
            startPeriod = activePeriod;
        }

        // Generate statuses for months from startPeriod to activePeriod + 12 months
        YearMonth endPeriod = activePeriod.plusMonths(12);
        YearMonth current = startPeriod;

        while (!current.isAfter(endPeriod)) {
            CashFlowInfo.MonthStatus monthStatus;

            if (snapshot.status() == CashFlow.CashFlowStatus.SETUP) {
                // In SETUP mode
                if (current.isBefore(activePeriod)) {
                    monthStatus = CashFlowInfo.MonthStatus.IMPORT_PENDING;
                } else if (current.equals(activePeriod)) {
                    monthStatus = CashFlowInfo.MonthStatus.ACTIVE;
                } else {
                    monthStatus = CashFlowInfo.MonthStatus.FORECASTED;
                }
            } else {
                // In OPEN mode
                if (current.isBefore(activePeriod)) {
                    // Could be IMPORTED, ROLLED_OVER, or ATTESTED depending on history
                    // For test purposes, assume ROLLED_OVER for past months
                    monthStatus = CashFlowInfo.MonthStatus.ROLLED_OVER;
                } else if (current.equals(activePeriod)) {
                    monthStatus = CashFlowInfo.MonthStatus.ACTIVE;
                } else {
                    monthStatus = CashFlowInfo.MonthStatus.FORECASTED;
                }
            }

            statuses.put(current, monthStatus);
            current = current.plusMonths(1);
        }

        return statuses;
    }

    private List<CashFlowInfo.CategoryInfo> mapCategories(List<Category> categories, Type type) {
        List<CashFlowInfo.CategoryInfo> result = new ArrayList<>();
        for (Category cat : categories) {
            result.add(mapCategory(cat, null, type));
        }
        return result;
    }

    private CashFlowInfo.CategoryInfo mapCategory(Category category, String parentName, Type type) {
        List<CashFlowInfo.CategoryInfo> subCategories = new ArrayList<>();
        if (category.getSubCategories() != null) {
            for (Category sub : category.getSubCategories()) {
                subCategories.add(mapCategory(sub, category.getCategoryName().name(), type));
            }
        }

        return new CashFlowInfo.CategoryInfo(
                category.getCategoryName().name(),
                parentName,
                type,
                category.isArchived(),
                subCategories
        );
    }
}
