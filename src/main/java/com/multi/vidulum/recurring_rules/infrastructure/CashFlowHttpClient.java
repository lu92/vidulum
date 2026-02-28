package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.exceptions.CashFlowCommunicationException;
import com.multi.vidulum.recurring_rules.domain.exceptions.CashFlowNotFoundException;
import com.multi.vidulum.recurring_rules.domain.exceptions.CategoryNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for communicating with CashFlow service.
 */
@Slf4j
@Component
public class CashFlowHttpClient {

    private final RestTemplate restTemplate;

    @Value("${cashflow.service.url:http://localhost:9090}")
    private String cashFlowServiceUrl;

    public CashFlowHttpClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Returns the CashFlow service URL. Can be overridden in tests.
     */
    protected String getCashFlowServiceUrl() {
        return cashFlowServiceUrl;
    }

    /**
     * Validates that the CashFlow exists and returns its categories.
     */
    public CashFlowInfo getCashFlowInfo(CashFlowId cashFlowId, String authToken)
            throws CashFlowNotFoundException, CashFlowCommunicationException {
        String url = getCashFlowServiceUrl() + "/cash-flow/cf=" + cashFlowId.id();

        try {
            HttpHeaders headers = createHeaders(authToken);
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new CashFlowNotFoundException(cashFlowId);
            }

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new CashFlowCommunicationException(cashFlowId, "get cash flow info", "Empty response body");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> inflowCategories = (List<Map<String, Object>>) body.get("inflowCategories");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outflowCategories = (List<Map<String, Object>>) body.get("outflowCategories");

            List<CategoryName> inflows = inflowCategories != null
                    ? inflowCategories.stream()
                            .map(cat -> {
                                @SuppressWarnings("unchecked")
                                Map<String, String> catName = (Map<String, String>) cat.get("categoryName");
                                return new CategoryName(catName.get("name"));
                            })
                            .toList()
                    : List.of();

            List<CategoryName> outflows = outflowCategories != null
                    ? outflowCategories.stream()
                            .map(cat -> {
                                @SuppressWarnings("unchecked")
                                Map<String, String> catName = (Map<String, String>) cat.get("categoryName");
                                return new CategoryName(catName.get("name"));
                            })
                            .toList()
                    : List.of();

            return new CashFlowInfo(cashFlowId, inflows, outflows);
        } catch (HttpClientErrorException.NotFound e) {
            throw new CashFlowNotFoundException(cashFlowId);
        } catch (CashFlowNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new CashFlowCommunicationException(cashFlowId, "get cash flow info", e);
        }
    }

    /**
     * Validates that a category exists in the CashFlow.
     */
    public void validateCategoryExists(CashFlowId cashFlowId, CategoryName categoryName, boolean isInflow, String authToken)
            throws CashFlowNotFoundException, CategoryNotFoundException, CashFlowCommunicationException {
        CashFlowInfo info = getCashFlowInfo(cashFlowId, authToken);

        List<CategoryName> categories = isInflow ? info.inflowCategories() : info.outflowCategories();
        boolean exists = categories.stream()
                .anyMatch(cat -> cat.name().equals(categoryName.name()));

        if (!exists) {
            throw new CategoryNotFoundException(cashFlowId, categoryName);
        }
    }

    /**
     * Creates an expected cash change in the CashFlow.
     */
    public CashChangeId createExpectedCashChange(
            CashFlowId cashFlowId,
            RecurringRuleId sourceRuleId,
            CategoryName categoryName,
            String name,
            String description,
            Money money,
            String type,
            ZonedDateTime dueDate,
            String authToken
    ) throws CashFlowCommunicationException {
        String url = getCashFlowServiceUrl() + "/cash-flow/expected-cash-change";

        try {
            HttpHeaders headers = createHeaders(authToken);

            // Use HashMap instead of Map.of() to allow null values
            Map<String, Object> request = new HashMap<>();
            request.put("cashFlowId", cashFlowId.id());
            request.put("sourceRuleId", sourceRuleId.id());
            request.put("category", categoryName.name());
            request.put("name", name);
            if (description != null) {
                request.put("description", description);
            }
            request.put("money", Map.of(
                    "amount", money.getAmount(),
                    "currency", money.getCurrency()
            ));
            request.put("type", type);
            request.put("dueDate", dueDate.toString());

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new CashFlowCommunicationException(cashFlowId, "create expected cash change", "Empty response body");
            }

            // Response structure: { "cashChangeId": "..." }
            String cashChangeIdStr = (String) body.get("cashChangeId");
            if (cashChangeIdStr == null) {
                throw new CashFlowCommunicationException(cashFlowId, "create expected cash change", "Missing cashChangeId in response");
            }

            return new CashChangeId(cashChangeIdStr);
        } catch (CashFlowCommunicationException e) {
            throw e;
        } catch (Exception e) {
            throw new CashFlowCommunicationException(cashFlowId, "create expected cash change", e);
        }
    }

    /**
     * Deletes an expected cash change from the CashFlow.
     */
    public void deleteExpectedCashChange(CashFlowId cashFlowId, CashChangeId cashChangeId, String authToken)
            throws CashFlowCommunicationException {
        String url = getCashFlowServiceUrl() + "/cash-flow/cf=" + cashFlowId.id() + "/cash-change/" + cashChangeId.id();

        try {
            HttpHeaders headers = createHeaders(authToken);

            restTemplate.exchange(
                    url,
                    HttpMethod.DELETE,
                    new HttpEntity<>(headers),
                    Void.class
            );
        } catch (HttpClientErrorException.NotFound e) {
            // Already deleted, ignore
            log.debug("Cash change {} already deleted or not found", cashChangeId.id());
        } catch (Exception e) {
            throw new CashFlowCommunicationException(cashFlowId, "delete cash change " + cashChangeId.id(), e);
        }
    }

    private HttpHeaders createHeaders(String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authToken != null && !authToken.isEmpty()) {
            headers.setBearerAuth(authToken);
        }
        return headers;
    }

    public record CashFlowInfo(
            CashFlowId cashFlowId,
            List<CategoryName> inflowCategories,
            List<CategoryName> outflowCategories
    ) {
    }
}
