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
                    ? extractAllCategories(inflowCategories)
                    : List.of();

            List<CategoryName> outflows = outflowCategories != null
                    ? extractAllCategories(outflowCategories)
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
     * Batch deletes expected cash changes from the CashFlow.
     * Only PENDING cash changes will be deleted; CONFIRMED ones are skipped.
     *
     * @param cashFlowId the CashFlow ID
     * @param sourceRuleId the recurring rule ID (for reference/logging)
     * @param cashChangeIds list of cash change IDs to delete
     * @param authToken the auth token
     * @return BatchDeleteResult with counts of deleted and skipped
     */
    public BatchDeleteResult batchDeleteExpectedCashChanges(
            CashFlowId cashFlowId,
            RecurringRuleId sourceRuleId,
            List<CashChangeId> cashChangeIds,
            String authToken
    ) throws CashFlowCommunicationException {
        if (cashChangeIds.isEmpty()) {
            return new BatchDeleteResult(0, 0);
        }

        String url = getCashFlowServiceUrl() + "/cash-flow/cf=" + cashFlowId.id() + "/cash-changes";

        try {
            HttpHeaders headers = createHeaders(authToken);

            Map<String, Object> request = new HashMap<>();
            request.put("sourceRuleId", sourceRuleId.id());
            request.put("cashChangeIds", cashChangeIds.stream().map(CashChangeId::id).toList());

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.DELETE,
                    new HttpEntity<>(request, headers),
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new CashFlowCommunicationException(cashFlowId, "batch delete cash changes", "Empty response body");
            }

            int deletedCount = ((Number) body.get("deletedCount")).intValue();
            int skippedCount = ((Number) body.get("skippedCount")).intValue();

            log.info("Batch deleted {} cash changes for rule {} (skipped {})",
                    deletedCount, sourceRuleId.id(), skippedCount);

            return new BatchDeleteResult(deletedCount, skippedCount);
        } catch (CashFlowCommunicationException e) {
            throw e;
        } catch (Exception e) {
            throw new CashFlowCommunicationException(cashFlowId, "batch delete cash changes", e);
        }
    }

    /**
     * Result of a batch delete operation.
     */
    public record BatchDeleteResult(int deletedCount, int skippedCount) {
    }

    /**
     * Batch updates expected cash changes in the CashFlow.
     * Only PENDING cash changes will be updated; CONFIRMED ones are skipped.
     *
     * @param cashFlowId the CashFlow ID
     * @param sourceRuleId the recurring rule ID (for reference/logging)
     * @param cashChangeIds list of cash change IDs to update
     * @param updates the updates to apply (amount, name, categoryName - all optional)
     * @param authToken the auth token
     * @return BatchUpdateResult with counts of updated and skipped
     */
    public BatchUpdateResult batchUpdateExpectedCashChanges(
            CashFlowId cashFlowId,
            RecurringRuleId sourceRuleId,
            List<CashChangeId> cashChangeIds,
            CashChangeUpdates updates,
            String authToken
    ) throws CashFlowCommunicationException {
        if (cashChangeIds.isEmpty()) {
            return new BatchUpdateResult(0, 0);
        }

        String url = getCashFlowServiceUrl() + "/cash-flow/cf=" + cashFlowId.id() + "/cash-changes/batch";

        try {
            HttpHeaders headers = createHeaders(authToken);

            Map<String, Object> updatesMap = new HashMap<>();
            if (updates.amount() != null) {
                updatesMap.put("amount", Map.of(
                        "amount", updates.amount().getAmount(),
                        "currency", updates.amount().getCurrency()
                ));
            }
            if (updates.name() != null) {
                updatesMap.put("name", updates.name());
            }
            if (updates.categoryName() != null) {
                updatesMap.put("categoryName", updates.categoryName().name());
            }

            Map<String, Object> request = new HashMap<>();
            request.put("sourceRuleId", sourceRuleId.id());
            request.put("cashChangeIds", cashChangeIds.stream().map(CashChangeId::id).toList());
            request.put("updates", updatesMap);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.PATCH,
                    new HttpEntity<>(request, headers),
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new CashFlowCommunicationException(cashFlowId, "batch update cash changes", "Empty response body");
            }

            int updatedCount = ((Number) body.get("updatedCount")).intValue();
            int skippedCount = ((Number) body.get("skippedCount")).intValue();

            log.info("Batch updated {} cash changes for rule {} (skipped {})",
                    updatedCount, sourceRuleId.id(), skippedCount);

            return new BatchUpdateResult(updatedCount, skippedCount);
        } catch (CashFlowCommunicationException e) {
            throw e;
        } catch (Exception e) {
            throw new CashFlowCommunicationException(cashFlowId, "batch update cash changes", e);
        }
    }

    /**
     * Updates to apply in a batch update operation.
     * All fields are optional - only non-null fields will be updated.
     */
    public record CashChangeUpdates(
            Money amount,
            String name,
            CategoryName categoryName
    ) {
        public static CashChangeUpdates ofAmount(Money amount) {
            return new CashChangeUpdates(amount, null, null);
        }

        public static CashChangeUpdates ofName(String name) {
            return new CashChangeUpdates(null, name, null);
        }

        public static CashChangeUpdates ofCategory(CategoryName categoryName) {
            return new CashChangeUpdates(null, null, categoryName);
        }
    }

    /**
     * Result of a batch update operation.
     */
    public record BatchUpdateResult(int updatedCount, int skippedCount) {
    }

    private HttpHeaders createHeaders(String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authToken != null && !authToken.isEmpty()) {
            headers.setBearerAuth(authToken);
        }
        return headers;
    }

    /**
     * Recursively extracts all category names from the category tree structure.
     * Each category has a "categoryName" field and optionally "subCategories" containing nested categories.
     */
    @SuppressWarnings("unchecked")
    private List<CategoryName> extractAllCategories(List<Map<String, Object>> categories) {
        List<CategoryName> result = new java.util.ArrayList<>();
        for (Map<String, Object> category : categories) {
            // Extract current category name
            Map<String, String> catName = (Map<String, String>) category.get("categoryName");
            if (catName != null && catName.get("name") != null) {
                result.add(new CategoryName(catName.get("name")));
            }

            // Recursively extract nested categories from "subCategories"
            List<Map<String, Object>> subCategories = (List<Map<String, Object>>) category.get("subCategories");
            if (subCategories != null && !subCategories.isEmpty()) {
                result.addAll(extractAllCategories(subCategories));
            }
        }
        return result;
    }

    public record CashFlowInfo(
            CashFlowId cashFlowId,
            List<CategoryName> inflowCategories,
            List<CategoryName> outflowCategories
    ) {
    }
}
