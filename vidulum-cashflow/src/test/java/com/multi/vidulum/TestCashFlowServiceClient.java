package com.multi.vidulum;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.CashChangeId;import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient.RollbackResult;
import com.multi.vidulum.cashflow.app.commands.comment.create.CreateCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.importhistorical.ImportHistoricalCashChangeCommand;
import com.multi.vidulum.cashflow.app.queries.GetCashFlowQuery;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * Test implementation of CashFlowServiceClient that uses QueryGateway and CommandGateway.
 * Used in integration tests to avoid HTTP communication overhead.
 *
 * <p>Both gateways arrive eagerly. This class used to need {@code @Lazy} on the command gateway for
 * the cycle CommandGateway -> ConfigureCategoryMappingCommandHandler -> CashFlowServiceClient ->
 * CommandGateway; {@code CqrsGatewayConfiguration} removed it by registering handlers after the
 * singletons exist instead of while a gateway is being built.
 */
@Slf4j
@AllArgsConstructor
public class TestCashFlowServiceClient implements CashFlowServiceClient {

    private final QueryGateway queryGateway;
    private final CommandGateway commandGateway;

    @Override
    public CashFlowInfo getCashFlowInfo(String cashFlowId) {
        CashFlowSnapshot snapshot = queryGateway.send(new GetCashFlowQuery(new CashFlowId(cashFlowId)));
        return mapToCashFlowInfo(snapshot);
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
                snapshot.userId().getId(),
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
