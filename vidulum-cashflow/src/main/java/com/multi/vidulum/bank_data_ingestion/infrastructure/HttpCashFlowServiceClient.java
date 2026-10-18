package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.cashflow.api.Type;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTTP implementation of CashFlowServiceClient.
 * <p>
 * This implementation makes HTTP calls to the cashflow-service REST API.
 * Used when bank-data-ingestion runs as a separate microservice.
 * <p>
 * Activated with Spring profile: "microservice" or "http-client"
 */
@Slf4j
public class HttpCashFlowServiceClient implements CashFlowServiceClient {

    private final RestClient restClient;
    private final String baseUrl;
    private String staticAuthToken;

    public HttpCashFlowServiceClient(RestClient.Builder restClientBuilder, String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    // Propagate Authorization header from incoming request or use static token
                    String authHeader = getAuthorizationHeader();
                    if (authHeader != null) {
                        request.getHeaders().add("Authorization", authHeader);
                    }
                    return execution.execute(request, body);
                })
                .build();
        log.info("Initialized HttpCashFlowServiceClient with baseUrl: {}", baseUrl);
    }

    /**
     * Sets a static JWT token for testing purposes.
     * When set, this token will be used instead of extracting from request context.
     *
     * @param token the JWT access token (without "Bearer " prefix)
     */
    public void setJwtToken(String token) {
        this.staticAuthToken = "Bearer " + token;
        log.debug("Static JWT token set for HttpCashFlowServiceClient");
    }

    /**
     * Extract Authorization header from current HTTP request context,
     * or return static token if set (for testing).
     */
    private String getAuthorizationHeader() {
        // First check for static token (testing)
        if (staticAuthToken != null) {
            return staticAuthToken;
        }

        // Otherwise try to extract from request context
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                return request.getHeader("Authorization");
            }
        } catch (Exception e) {
            log.warn("Could not extract Authorization header from request context: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public CashFlowInfo getCashFlowInfo(String cashFlowId) {
        try {
            CashFlowResponse response = restClient.get()
                    .uri("/cash-flow/cf={cashFlowId}", cashFlowId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(CashFlowResponse.class);

            if (response == null) {
                throw new CashFlowNotFoundException(CashFlowId.of(cashFlowId));
            }

            // Fetch month statuses from CashFlowForecastProcessor
            Map<String, String> monthStatuses = fetchMonthStatuses(cashFlowId);

            return mapToInfo(response, monthStatuses);

        } catch (HttpClientErrorException.NotFound e) {
            throw new CashFlowNotFoundException(CashFlowId.of(cashFlowId));
        } catch (HttpClientErrorException e) {
            log.error("HTTP error getting CashFlow [{}]: {} - {}", cashFlowId, e.getStatusCode(), e.getMessage());
            throw new RuntimeException("Failed to get CashFlow info: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch month statuses from CashFlowForecastProcessor.
     * Returns empty map if forecast not found (graceful degradation).
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> fetchMonthStatuses(String cashFlowId) {
        try {
            MonthStatusesResponse response = restClient.get()
                    .uri("/cash-flow-forecast/cf={cashFlowId}/month-statuses", cashFlowId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(MonthStatusesResponse.class);

            if (response == null || response.monthStatuses() == null) {
                return Map.of();
            }

            // Convert YearMonth keys (serialized as strings like "2025-02") to String map
            Map<String, String> result = new java.util.HashMap<>();
            for (Map.Entry<String, String> entry : response.monthStatuses().entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            log.warn("Could not fetch month statuses for CashFlow [{}]: {}. Returning empty map.",
                    cashFlowId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Response DTO for month statuses endpoint.
     * Matches CashFlowForecastDto.MonthStatusesResponse.
     */
    private record MonthStatusesResponse(
            String cashFlowId,
            Map<String, String> monthStatuses
    ) {
    }


    // Response DTOs matching CashFlowRestController responses

    private record CashFlowResponse(
            String cashFlowId,
            String userId,
            String name,
            String description,
            String status,
            List<CategoryResponse> inflowCategories,
            List<CategoryResponse> outflowCategories,
            Map<String, Object> cashChanges,
            YearMonth activePeriod,
            YearMonth startPeriod,
            Map<String, String> monthStatuses
    ) {
    }

    private record CategoryResponse(
            CategoryNameDto categoryName,
            Object budgeting,
            List<CategoryResponse> subCategories,
            boolean modifiable,
            String validFrom,
            String validTo,
            boolean archived,
            String origin,
            boolean active
    ) {
    }

    private record CategoryNameDto(String name) {
    }

    // ============ Mapping Methods ============

    private CashFlowInfo mapToInfo(CashFlowResponse response, Map<String, String> monthStatuses) {
        return new CashFlowInfo(
                response.cashFlowId(),
                response.userId(),
                mapStatus(response.status()),
                response.activePeriod(),
                response.startPeriod(),
                mapCategories(response.inflowCategories(), Type.INFLOW),
                mapCategories(response.outflowCategories(), Type.OUTFLOW),
                extractTransactionIds(response),
                response.cashChanges() != null ? response.cashChanges().size() : 0,
                mapMonthStatuses(monthStatuses)
        );
    }

    private Map<YearMonth, CashFlowInfo.MonthStatus> mapMonthStatuses(Map<String, String> monthStatuses) {
        if (monthStatuses == null) {
            return Map.of();
        }
        Map<YearMonth, CashFlowInfo.MonthStatus> result = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : monthStatuses.entrySet()) {
            try {
                YearMonth month = YearMonth.parse(entry.getKey());
                CashFlowInfo.MonthStatus status = mapMonthStatus(entry.getValue());
                result.put(month, status);
            } catch (Exception e) {
                log.warn("Failed to parse month status: {} = {}", entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private CashFlowInfo.MonthStatus mapMonthStatus(String status) {
        if (status == null) {
            return CashFlowInfo.MonthStatus.FORECASTED;
        }
        return switch (status.toUpperCase()) {
            case "IMPORT_PENDING" -> CashFlowInfo.MonthStatus.IMPORT_PENDING;
            case "IMPORTED" -> CashFlowInfo.MonthStatus.IMPORTED;
            case "ROLLED_OVER" -> CashFlowInfo.MonthStatus.ROLLED_OVER;
            case "ATTESTED" -> CashFlowInfo.MonthStatus.ATTESTED;
            case "ACTIVE" -> CashFlowInfo.MonthStatus.ACTIVE;
            case "FORECASTED" -> CashFlowInfo.MonthStatus.FORECASTED;
            default -> CashFlowInfo.MonthStatus.FORECASTED;
        };
    }

    private CashFlowInfo.CashFlowStatus mapStatus(String status) {
        if (status == null) {
            return CashFlowInfo.CashFlowStatus.OPEN;
        }
        return switch (status.toUpperCase()) {
            case "SETUP" -> CashFlowInfo.CashFlowStatus.SETUP;
            case "OPEN" -> CashFlowInfo.CashFlowStatus.OPEN;
            case "CLOSED" -> CashFlowInfo.CashFlowStatus.CLOSED;
            default -> CashFlowInfo.CashFlowStatus.OPEN;
        };
    }

    private List<CashFlowInfo.CategoryInfo> mapCategories(List<CategoryResponse> categories, Type type) {
        if (categories == null) {
            return List.of();
        }
        return categories.stream()
                .map(cat -> mapCategory(cat, null, type))
                .toList();
    }

    private CashFlowInfo.CategoryInfo mapCategory(CategoryResponse category, String parentName, Type type) {
        String catName = category.categoryName() != null ? category.categoryName().name() : null;
        return new CashFlowInfo.CategoryInfo(
                catName,
                parentName,
                type,
                category.archived(),
                category.subCategories() != null
                        ? category.subCategories().stream()
                        .map(sub -> mapCategory(sub, catName, type))
                        .toList()
                        : List.of()
        );
    }

    /**
     * Which source transactions this cash flow already holds.
     *
     * <p>This used to {@code return new HashSet<>()} with a comment saying the API might report
     * these one day. It meant the duplicate check in {@code StageTransactionsCommandHandler} -
     * {@code existingBankTransactionIds.contains(...)} - was always comparing against nothing, so
     * {@code TransactionValidation.isDuplicate} had a full code path that could never run. The
     * endpoint reports the ids now, because cashflow finally stores them.
     */
    private Set<String> extractTransactionIds(CashFlowResponse response) {
        if (response.cashChanges() == null) {
            return Set.of();
        }
        return response.cashChanges().values().stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(cashChange -> cashChange.get("bankTransactionId"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toSet());
    }
}
