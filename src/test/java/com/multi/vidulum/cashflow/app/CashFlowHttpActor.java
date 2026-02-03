package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.BankName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.error.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.YearMonth;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP Actor for CashFlow API testing.
 * Encapsulates all REST interactions with /cash-flow endpoints.
 * Follows the same pattern as AuthenticationHttpActor.
 */
@Slf4j
public class CashFlowHttpActor {

    private final TestRestTemplate restTemplate;
    private final RestTemplate rawRestTemplate;
    private final String baseUrl;

    public CashFlowHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
        this.rawRestTemplate = createRawRestTemplate();
    }

    private RestTemplate createRawRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        return new RestTemplate(factory);
    }

    // ============ CashFlow Operations ============

    /**
     * Creates a CashFlow with history support via HTTP.
     * The CashFlow will be in SETUP mode, ready for historical data import.
     */
    public String createCashFlowWithHistory(String userId, String name, YearMonth startPeriod, Money initialBalance) {
        CashFlowDto.CreateCashFlowWithHistoryJson request = CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                .userId(userId)
                .name(name)
                .description("CashFlow for HTTP integration testing")
                .bankAccount(new BankAccount(
                        new BankName("Test Bank"),
                        new BankAccountNumber("PL12345678901234567890123456", Currency.of(initialBalance.getCurrency())),
                        Money.zero(initialBalance.getCurrency())
                ))
                .startPeriod(startPeriod.toString())
                .initialBalance(initialBalance)
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/cash-flow/with-history",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cashFlowId = response.getBody();
        assertThat(cashFlowId).isNotNull().isNotEmpty();

        log.info("Created CashFlow with history via HTTP: id={}, name={}, startPeriod={}",
                cashFlowId, name, startPeriod);
        return cashFlowId;
    }

    /**
     * Creates a category in a CashFlow via HTTP.
     * Uses isImport=true to allow category creation in SETUP mode.
     */
    public void createCategory(String cashFlowId, String categoryName, Type type) {
        CashFlowDto.CreateCategoryJson request = CashFlowDto.CreateCategoryJson.builder()
                .category(categoryName)
                .type(type)
                .build();

        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/cash-flow/" + cashFlowId + "/category?isImport=true",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.debug("Created category via HTTP: cashFlowId={}, category={}, type={}",
                cashFlowId, categoryName, type);
    }

    /**
     * Imports a historical transaction via HTTP.
     */
    public String importHistoricalTransaction(String cashFlowId, String categoryName, String name,
                                              String description, Money money, Type type,
                                              ZonedDateTime dueDate, ZonedDateTime paidDate) {
        CashFlowDto.ImportHistoricalCashChangeJson request = CashFlowDto.ImportHistoricalCashChangeJson.builder()
                .category(categoryName)
                .name(name)
                .description(description)
                .money(money)
                .type(type)
                .dueDate(dueDate)
                .paidDate(paidDate)
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/cash-flow/" + cashFlowId + "/import-historical",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cashChangeId = response.getBody();
        assertThat(cashChangeId).isNotNull().isNotEmpty();

        log.debug("Imported historical transaction via HTTP: cashFlowId={}, cashChangeId={}, category={}",
                cashFlowId, cashChangeId, categoryName);
        return cashChangeId;
    }

    /**
     * Attests historical import - transitions CashFlow from SETUP to OPEN mode.
     */
    public ResponseEntity<CashFlowDto.AttestHistoricalImportResponseJson> attestHistoricalImport(
            String cashFlowId, Money confirmedBalance, boolean forceAttestation, boolean createAdjustment) {

        CashFlowDto.AttestHistoricalImportJson request = CashFlowDto.AttestHistoricalImportJson.builder()
                .confirmedBalance(confirmedBalance)
                .forceAttestation(forceAttestation)
                .createAdjustment(createAdjustment)
                .build();

        return restTemplate.exchange(
                baseUrl + "/cash-flow/" + cashFlowId + "/attest-historical-import",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                CashFlowDto.AttestHistoricalImportResponseJson.class
        );
    }

    /**
     * Attests historical import expecting an error response.
     * Uses rawRestTemplate to capture error responses properly.
     */
    public ResponseEntity<ApiError> attestHistoricalImportExpectingError(
            String cashFlowId, Money confirmedBalance, boolean forceAttestation, boolean createAdjustment) {

        CashFlowDto.AttestHistoricalImportJson request = CashFlowDto.AttestHistoricalImportJson.builder()
                .confirmedBalance(confirmedBalance)
                .forceAttestation(forceAttestation)
                .createAdjustment(createAdjustment)
                .build();

        HttpHeaders headers = jsonHeaders();
        HttpEntity<CashFlowDto.AttestHistoricalImportJson> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<ApiError> response = rawRestTemplate.exchange(
                    baseUrl + "/cash-flow/" + cashFlowId + "/attest-historical-import",
                    HttpMethod.POST,
                    entity,
                    ApiError.class
            );
            return response;
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAs(ApiError.class));
        }
    }

    /**
     * Gets CashFlow summary via HTTP.
     */
    public CashFlowDto.CashFlowSummaryJson getCashFlow(String cashFlowId) {
        ResponseEntity<CashFlowDto.CashFlowSummaryJson> response = restTemplate.getForEntity(
                baseUrl + "/cash-flow/" + cashFlowId,
                CashFlowDto.CashFlowSummaryJson.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Gets CashFlow expecting an error response.
     */
    public ResponseEntity<ApiError> getCashFlowExpectingError(String cashFlowId) {
        return executeExpectingError(
                baseUrl + "/cash-flow/" + cashFlowId,
                HttpMethod.GET,
                null
        );
    }

    // ============ Error-Expecting Operations ============

    /**
     * Creates CashFlow with history expecting an error response.
     */
    public ResponseEntity<ApiError> createCashFlowWithHistoryExpectingError(String userId, String name,
                                                                             String startPeriod, Money initialBalance) {
        CashFlowDto.CreateCashFlowWithHistoryJson request = CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                .userId(userId)
                .name(name)
                .description("CashFlow for HTTP integration testing")
                .bankAccount(new BankAccount(
                        new BankName("Test Bank"),
                        new BankAccountNumber("PL12345678901234567890123456", Currency.of(initialBalance.getCurrency())),
                        Money.zero(initialBalance.getCurrency())
                ))
                .startPeriod(startPeriod)
                .initialBalance(initialBalance)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/with-history",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Appends expected cash change expecting an error response.
     */
    public ResponseEntity<ApiError> appendExpectedCashChangeExpectingError(String cashFlowId, String category,
                                                                            String name, String description,
                                                                            Money money, Type type,
                                                                            ZonedDateTime dueDate) {
        CashFlowDto.AppendExpectedCashChangeJson request = CashFlowDto.AppendExpectedCashChangeJson.builder()
                .cashFlowId(cashFlowId)
                .category(category)
                .name(name)
                .description(description)
                .money(money)
                .type(type)
                .dueDate(dueDate)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/expected-cash-change",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Appends paid cash change expecting an error response.
     */
    public ResponseEntity<ApiError> appendPaidCashChangeExpectingError(String cashFlowId, String category,
                                                                        String name, String description,
                                                                        Money money, Type type,
                                                                        ZonedDateTime dueDate, ZonedDateTime paidDate) {
        CashFlowDto.AppendPaidCashChangeJson request = CashFlowDto.AppendPaidCashChangeJson.builder()
                .cashFlowId(cashFlowId)
                .category(category)
                .name(name)
                .description(description)
                .money(money)
                .type(type)
                .dueDate(dueDate)
                .paidDate(paidDate)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/paid-cash-change",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Edits cash change expecting an error response.
     */
    public ResponseEntity<ApiError> editCashChangeExpectingError(String cashFlowId, String cashChangeId,
                                                                  String name, String description,
                                                                  Money money, String category,
                                                                  ZonedDateTime dueDate) {
        CashFlowDto.EditCashChangeJson request = CashFlowDto.EditCashChangeJson.builder()
                .cashFlowId(cashFlowId)
                .cashChangeId(cashChangeId)
                .name(name)
                .description(description)
                .money(money)
                .category(category)
                .dueDate(dueDate)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/edit",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Confirms cash change expecting an error response.
     */
    public ResponseEntity<ApiError> confirmCashChangeExpectingError(String cashFlowId, String cashChangeId) {
        CashFlowDto.ConfirmCashChangeJson request = CashFlowDto.ConfirmCashChangeJson.builder()
                .cashFlowId(cashFlowId)
                .cashChangeId(cashChangeId)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/confirm",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Rejects cash change expecting an error response.
     */
    public ResponseEntity<ApiError> rejectCashChangeExpectingError(String cashFlowId, String cashChangeId, String reason) {
        CashFlowDto.RejectCashChangeJson request = CashFlowDto.RejectCashChangeJson.builder()
                .cashFlowId(cashFlowId)
                .cashChangeId(cashChangeId)
                .reason(reason)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/reject",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Creates category expecting an error response.
     */
    public ResponseEntity<ApiError> createCategoryExpectingError(String cashFlowId, String categoryName,
                                                                  Type type, boolean isImport) {
        CashFlowDto.CreateCategoryJson request = CashFlowDto.CreateCategoryJson.builder()
                .category(categoryName)
                .type(type)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/" + cashFlowId + "/category?isImport=" + isImport,
                HttpMethod.POST,
                request
        );
    }

    /**
     * Archives category expecting an error response.
     */
    public ResponseEntity<ApiError> archiveCategoryExpectingError(String cashFlowId, String categoryName,
                                                                   Type categoryType, boolean forceArchiveChildren) {
        CashFlowDto.ArchiveCategoryJson request = CashFlowDto.ArchiveCategoryJson.builder()
                .categoryName(categoryName)
                .categoryType(categoryType)
                .forceArchiveChildren(forceArchiveChildren)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/" + cashFlowId + "/category/archive",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Unarchives category expecting an error response.
     */
    public ResponseEntity<ApiError> unarchiveCategoryExpectingError(String cashFlowId, String categoryName,
                                                                     Type categoryType) {
        CashFlowDto.UnarchiveCategoryJson request = CashFlowDto.UnarchiveCategoryJson.builder()
                .categoryName(categoryName)
                .categoryType(categoryType)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/" + cashFlowId + "/category/unarchive",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Sets budgeting expecting an error response.
     */
    public ResponseEntity<ApiError> setBudgetingExpectingError(String cashFlowId, String categoryName,
                                                                Type categoryType, Money budget) {
        CashFlowDto.SetBudgetingJson request = CashFlowDto.SetBudgetingJson.builder()
                .cashFlowId(cashFlowId)
                .categoryName(categoryName)
                .categoryType(categoryType)
                .budget(budget)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/budgeting",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Updates budgeting expecting an error response.
     */
    public ResponseEntity<ApiError> updateBudgetingExpectingError(String cashFlowId, String categoryName,
                                                                   Type categoryType, Money newBudget) {
        CashFlowDto.UpdateBudgetingJson request = CashFlowDto.UpdateBudgetingJson.builder()
                .cashFlowId(cashFlowId)
                .categoryName(categoryName)
                .categoryType(categoryType)
                .newBudget(newBudget)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/budgeting",
                HttpMethod.PUT,
                request
        );
    }

    /**
     * Removes budgeting expecting an error response.
     */
    public ResponseEntity<ApiError> removeBudgetingExpectingError(String cashFlowId, String categoryName,
                                                                   Type categoryType) {
        CashFlowDto.RemoveBudgetingJson request = CashFlowDto.RemoveBudgetingJson.builder()
                .cashFlowId(cashFlowId)
                .categoryName(categoryName)
                .categoryType(categoryType)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/budgeting",
                HttpMethod.DELETE,
                request
        );
    }

    /**
     * Imports historical transaction expecting an error response.
     */
    public ResponseEntity<ApiError> importHistoricalTransactionExpectingError(String cashFlowId, String categoryName,
                                                                               String name, String description,
                                                                               Money money, Type type,
                                                                               ZonedDateTime dueDate, ZonedDateTime paidDate) {
        CashFlowDto.ImportHistoricalCashChangeJson request = CashFlowDto.ImportHistoricalCashChangeJson.builder()
                .category(categoryName)
                .name(name)
                .description(description)
                .money(money)
                .type(type)
                .dueDate(dueDate)
                .paidDate(paidDate)
                .build();

        return executeExpectingError(
                baseUrl + "/cash-flow/" + cashFlowId + "/import-historical",
                HttpMethod.POST,
                request
        );
    }

    /**
     * Rollbacks import expecting an error response.
     * Note: DELETE with body can cause issues, so we use null body for simple cases.
     */
    public ResponseEntity<ApiError> rollbackImportExpectingError(String cashFlowId) {
        return executeExpectingError(
                baseUrl + "/cash-flow/" + cashFlowId + "/import",
                HttpMethod.DELETE,
                null
        );
    }

    // ============ Success Operations (for test setup) ============

    /**
     * Appends expected cash change via HTTP.
     */
    public String appendExpectedCashChange(String cashFlowId, String category, String name,
                                           String description, Money money, Type type, ZonedDateTime dueDate) {
        CashFlowDto.AppendExpectedCashChangeJson request = CashFlowDto.AppendExpectedCashChangeJson.builder()
                .cashFlowId(cashFlowId)
                .category(category)
                .name(name)
                .description(description)
                .money(money)
                .type(type)
                .dueDate(dueDate)
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/cash-flow/expected-cash-change",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Appends paid cash change via HTTP.
     */
    public String appendPaidCashChange(String cashFlowId, String category, String name,
                                       String description, Money money, Type type,
                                       ZonedDateTime dueDate, ZonedDateTime paidDate) {
        CashFlowDto.AppendPaidCashChangeJson request = CashFlowDto.AppendPaidCashChangeJson.builder()
                .cashFlowId(cashFlowId)
                .category(category)
                .name(name)
                .description(description)
                .money(money)
                .type(type)
                .dueDate(dueDate)
                .paidDate(paidDate)
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/cash-flow/paid-cash-change",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Confirms cash change via HTTP.
     */
    public void confirmCashChange(String cashFlowId, String cashChangeId) {
        CashFlowDto.ConfirmCashChangeJson request = CashFlowDto.ConfirmCashChangeJson.builder()
                .cashFlowId(cashFlowId)
                .cashChangeId(cashChangeId)
                .build();

        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/cash-flow/confirm",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Archives category via HTTP.
     */
    public void archiveCategory(String cashFlowId, String categoryName, Type categoryType, boolean forceArchiveChildren) {
        CashFlowDto.ArchiveCategoryJson request = CashFlowDto.ArchiveCategoryJson.builder()
                .categoryName(categoryName)
                .categoryType(categoryType)
                .forceArchiveChildren(forceArchiveChildren)
                .build();

        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/cash-flow/" + cashFlowId + "/category/archive",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Sets budgeting via HTTP.
     */
    public void setBudgeting(String cashFlowId, String categoryName, Type categoryType, Money budget) {
        CashFlowDto.SetBudgetingJson request = CashFlowDto.SetBudgetingJson.builder()
                .cashFlowId(cashFlowId)
                .categoryName(categoryName)
                .categoryType(categoryType)
                .budget(budget)
                .build();

        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/cash-flow/budgeting",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Creates a standard CashFlow (not with history) via HTTP.
     */
    public String createCashFlow(String userId, String name, String currency) {
        CashFlowDto.CreateCashFlowJson request = CashFlowDto.CreateCashFlowJson.builder()
                .userId(userId)
                .name(name)
                .description("CashFlow for HTTP integration testing")
                .bankAccount(new BankAccount(
                        new BankName("Test Bank"),
                        new BankAccountNumber("PL12345678901234567890123456", Currency.of(currency)),
                        Money.zero(currency)
                ))
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/cash-flow",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cashFlowId = response.getBody();
        assertThat(cashFlowId).isNotNull().isNotEmpty();

        log.info("Created CashFlow via HTTP: id={}, name={}", cashFlowId, name);
        return cashFlowId;
    }

    // ============ Helper Methods ============

    private <T> ResponseEntity<ApiError> executeExpectingError(String url, HttpMethod method, T body) {
        HttpEntity<T> entity = body != null ? new HttpEntity<>(body, jsonHeaders()) : new HttpEntity<>(jsonHeaders());

        try {
            ResponseEntity<ApiError> response = rawRestTemplate.exchange(
                    url,
                    method,
                    entity,
                    ApiError.class
            );
            return response;
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAs(ApiError.class));
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
