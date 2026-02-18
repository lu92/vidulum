package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * HTTP-based implementation of CashFlowServiceClient for integration tests.
 * Uses TestRestTemplate to communicate with cashflow-service via REST API.
 *
 * This implementation is used when testing the full HTTP flow, including
 * bank-data-ingestion -> cashflow-service communication.
 */
@Slf4j
public class HttpTestCashFlowServiceClient implements CashFlowServiceClient {

    private final TestRestTemplate restTemplate;
    private final String baseUrl;

    public HttpTestCashFlowServiceClient(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
    }

    @Override
    public CashFlowInfo getCashFlowInfo(String cashFlowId) {
        ResponseEntity<CashFlowDto.CashFlowSummaryJson> response = restTemplate.getForEntity(
                baseUrl + "/cash-flow/cf=" + cashFlowId,
                CashFlowDto.CashFlowSummaryJson.class
        );

        if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
            throw new CashFlowNotFoundException(cashFlowId);
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to get CashFlow: " + response.getStatusCode());
        }

        CashFlowDto.CashFlowSummaryJson body = response.getBody();
        return mapToCashFlowInfo(body);
    }

    @Override
    public boolean exists(String cashFlowId) {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    baseUrl + "/cash-flow/cf=" + cashFlowId,
                    Map.class
            );
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void createCategory(String cashFlowId, String categoryName, String parentCategoryName, Type type) {
        CashFlowDto.CreateCategoryJson request = CashFlowDto.CreateCategoryJson.builder()
                .category(categoryName)
                .parentCategoryName(parentCategoryName)
                .type(type)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/cash-flow/cf=" + cashFlowId + "/category",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Void.class
        );

        if (response.getStatusCode() == HttpStatus.CONFLICT) {
            throw new CategoryAlreadyExistsException(categoryName);
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to create category: " + response.getStatusCode());
        }

        log.debug("Created category {} (parent: {}) for CashFlow {} via HTTP",
                categoryName, parentCategoryName, cashFlowId);
    }

    @Override
    public String importHistoricalTransaction(String cashFlowId, ImportTransactionRequest request) {
        CashFlowDto.ImportHistoricalCashChangeJson httpRequest = CashFlowDto.ImportHistoricalCashChangeJson.builder()
                .category(request.categoryName())
                .name(request.name())
                .description(request.description())
                .money(Money.of(request.amount(), request.currency()))
                .type(request.type())
                .dueDate(request.dueDate().atStartOfDay(ZoneId.systemDefault()))
                .paidDate(request.paidDate().atStartOfDay(ZoneId.systemDefault()))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/cash-flow/cf=" + cashFlowId + "/import-historical",
                HttpMethod.POST,
                new HttpEntity<>(httpRequest, headers),
                String.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to import historical transaction: " + response.getStatusCode());
        }

        String cashChangeId = response.getBody();
        log.debug("Imported historical transaction {} for CashFlow {} via HTTP", cashChangeId, cashFlowId);
        return cashChangeId;
    }

    @Override
    public RollbackResult rollbackImport(String cashFlowId, boolean deleteCategories) {
        // For tests, we get the current state after rollback
        CashFlowInfo infoAfter = getCashFlowInfo(cashFlowId);
        return new RollbackResult(0, 0, infoAfter);
    }

    private CashFlowInfo mapToCashFlowInfo(CashFlowDto.CashFlowSummaryJson summary) {
        List<CashFlowInfo.CategoryInfo> inflowCategories = mapCategories(summary.getInflowCategories(), null, Type.INFLOW);
        List<CashFlowInfo.CategoryInfo> outflowCategories = mapCategories(summary.getOutflowCategories(), null, Type.OUTFLOW);

        Set<String> existingTransactionIds = new HashSet<>();
        if (summary.getCashChanges() != null) {
            existingTransactionIds.addAll(summary.getCashChanges().keySet());
        }

        CashFlowInfo.CashFlowStatus status = switch (summary.getStatus()) {
            case SETUP -> CashFlowInfo.CashFlowStatus.SETUP;
            case OPEN -> CashFlowInfo.CashFlowStatus.OPEN;
            case CLOSED -> CashFlowInfo.CashFlowStatus.CLOSED;
        };

        // Generate simplified month statuses for tests
        Map<YearMonth, CashFlowInfo.MonthStatus> monthStatuses = generateMonthStatuses(
                summary.getStartPeriod(), summary.getActivePeriod(), summary.getStatus());

        return new CashFlowInfo(
                summary.getCashFlowId(),
                status,
                summary.getActivePeriod(),
                summary.getStartPeriod(),
                inflowCategories,
                outflowCategories,
                existingTransactionIds,
                summary.getCashChanges() != null ? summary.getCashChanges().size() : 0,
                monthStatuses
        );
    }

    private Map<YearMonth, CashFlowInfo.MonthStatus> generateMonthStatuses(
            YearMonth startPeriod, YearMonth activePeriod,
            com.multi.vidulum.cashflow.domain.CashFlow.CashFlowStatus domainStatus) {

        Map<YearMonth, CashFlowInfo.MonthStatus> statuses = new HashMap<>();

        if (startPeriod == null) {
            startPeriod = activePeriod;
        }

        YearMonth endPeriod = activePeriod.plusMonths(12);
        YearMonth current = startPeriod;

        while (!current.isAfter(endPeriod)) {
            CashFlowInfo.MonthStatus monthStatus;

            if (domainStatus == com.multi.vidulum.cashflow.domain.CashFlow.CashFlowStatus.SETUP) {
                if (current.isBefore(activePeriod)) {
                    monthStatus = CashFlowInfo.MonthStatus.IMPORT_PENDING;
                } else if (current.equals(activePeriod)) {
                    monthStatus = CashFlowInfo.MonthStatus.ACTIVE;
                } else {
                    monthStatus = CashFlowInfo.MonthStatus.FORECASTED;
                }
            } else {
                if (current.isBefore(activePeriod)) {
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

    private List<CashFlowInfo.CategoryInfo> mapCategories(
            List<Category> categories, String parentName, Type type) {
        if (categories == null) {
            return new ArrayList<>();
        }

        List<CashFlowInfo.CategoryInfo> result = new ArrayList<>();
        for (Category cat : categories) {
            result.add(mapCategory(cat, parentName, type));
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
