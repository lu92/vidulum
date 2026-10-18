package com.multi.vidulum;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.app.queries.GetCashFlowQuery;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

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

    private CashFlowInfo mapToCashFlowInfo(CashFlowSnapshot snapshot) {
        List<CashFlowInfo.CategoryInfo> inflowCategories = mapCategories(snapshot.inflowCategories(), Type.INFLOW);
        List<CashFlowInfo.CategoryInfo> outflowCategories = mapCategories(snapshot.outflowCategories(), Type.OUTFLOW);

        CashFlowInfo.CashFlowStatus status = switch (snapshot.status()) {
            case SETUP -> CashFlowInfo.CashFlowStatus.SETUP;
            case OPEN -> CashFlowInfo.CashFlowStatus.OPEN;
            case CLOSED -> CashFlowInfo.CashFlowStatus.CLOSED;
        };

        return new CashFlowInfo(
                snapshot.cashFlowId().id(),
                snapshot.userId().getId(),
                status,
                snapshot.activePeriod(),
                snapshot.startPeriod(),
                inflowCategories,
                outflowCategories,
                snapshot.cashChanges().size()
        );
    }

    // generateMonthStatuses is gone, and it is worth saying why rather than just deleting it. It
    // invented statuses from a heuristic - "past months in OPEN mode are ROLLED_OVER" - and its
    // own comment admitted it could not produce IMPORTED or ATTESTED. Staging refuses an import
    // into an ATTESTED month, so for as long as this double answered the question, that rule could
    // not fire in any test: the refusal was unreachable, not untested (task F38). Month statuses
    // now come from CashFlowForecastApi, whose in-process implementation reads the statement this
    // test context really builds from the events it really emits.

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
