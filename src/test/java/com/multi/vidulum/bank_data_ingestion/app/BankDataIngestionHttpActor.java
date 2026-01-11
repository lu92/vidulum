package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.BankName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actor for HTTP-based integration tests of bank-data-ingestion module.
 * Encapsulates REST API calls to both cashflow-service and bank-data-ingestion endpoints.
 *
 * This follows the same pattern as DualBudgetActor for consistency across tests.
 */
@Slf4j
public class BankDataIngestionHttpActor {

    private final TestRestTemplate restTemplate;
    private final String baseUrl;

    public BankDataIngestionHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
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
     * Gets CashFlow info via HTTP.
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
     * Gets CashFlow info as a raw Map (useful when avoiding serialization issues).
     */
    public Map<String, Object> getCashFlowAsMap(String cashFlowId) {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                baseUrl + "/cash-flow/" + cashFlowId,
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Creates a category in a CashFlow via HTTP.
     */
    public void createCategory(String cashFlowId, String categoryName, Type type) {
        createCategory(cashFlowId, categoryName, null, type);
    }

    /**
     * Creates a subcategory in a CashFlow via HTTP.
     * Uses isImport=true to allow category creation in SETUP mode (for import testing).
     */
    public void createCategory(String cashFlowId, String categoryName, String parentCategoryName, Type type) {
        CashFlowDto.CreateCategoryJson request = CashFlowDto.CreateCategoryJson.builder()
                .category(categoryName)
                .parentCategoryName(parentCategoryName)
                .type(type)
                .build();

        // Use isImport=true to allow category creation in SETUP mode
        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/cash-flow/" + cashFlowId + "/category?isImport=true",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.debug("Created category via HTTP (import mode): cashFlowId={}, category={}, parent={}, type={}",
                cashFlowId, categoryName, parentCategoryName, type);
    }

    /**
     * Imports a historical transaction via HTTP.
     * Returns the created CashChangeId.
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

    // ============ Bank Data Ingestion Operations ============

    /**
     * Configures category mappings for a CashFlow.
     */
    public void configureMappings(String cashFlowId, List<BankDataIngestionDto.MappingConfigJson> mappings) {
        BankDataIngestionDto.ConfigureMappingsRequest request = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(mappings)
                .build();

        ResponseEntity<BankDataIngestionDto.ConfigureMappingsResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-ingestion/" + cashFlowId + "/mappings",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                BankDataIngestionDto.ConfigureMappingsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Configured {} mappings for CashFlow {}", mappings.size(), cashFlowId);
    }

    /**
     * Creates a mapping that will create a new category during import.
     * Note: MAP_TO_EXISTING was removed - each CashFlow can only have one file import.
     */
    public BankDataIngestionDto.MappingConfigJson mappingCreateNewCategory(String bankCategory, String targetCategory, Type type) {
        return BankDataIngestionDto.MappingConfigJson.builder()
                .bankCategoryName(bankCategory)
                .action(MappingAction.CREATE_NEW)
                .targetCategoryName(targetCategory)
                .categoryType(type)
                .build();
    }

    /**
     * Creates a mapping that will create a new category during import.
     */
    public BankDataIngestionDto.MappingConfigJson mappingCreateNew(String bankCategory, String newCategoryName, Type type) {
        return BankDataIngestionDto.MappingConfigJson.builder()
                .bankCategoryName(bankCategory)
                .action(MappingAction.CREATE_NEW)
                .targetCategoryName(newCategoryName)
                .categoryType(type)
                .build();
    }

    /**
     * Creates a mapping that will create a subcategory during import.
     */
    public BankDataIngestionDto.MappingConfigJson mappingCreateSubcategory(String bankCategory, String subcategoryName,
                                                                           String parentCategory, Type type) {
        return BankDataIngestionDto.MappingConfigJson.builder()
                .bankCategoryName(bankCategory)
                .action(MappingAction.CREATE_SUBCATEGORY)
                .targetCategoryName(subcategoryName)
                .parentCategoryName(parentCategory)
                .categoryType(type)
                .build();
    }

    /**
     * Gets category mappings for a CashFlow.
     */
    public BankDataIngestionDto.GetMappingsResponse getMappings(String cashFlowId) {
        ResponseEntity<BankDataIngestionDto.GetMappingsResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/v1/bank-data-ingestion/" + cashFlowId + "/mappings",
                BankDataIngestionDto.GetMappingsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Lists active staging sessions for a CashFlow.
     * Allows users to return to unfinished imports.
     */
    public BankDataIngestionDto.ListStagingSessionsResponse listStagingSessions(String cashFlowId) {
        ResponseEntity<BankDataIngestionDto.ListStagingSessionsResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/v1/bank-data-ingestion/" + cashFlowId + "/staging",
                BankDataIngestionDto.ListStagingSessionsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BankDataIngestionDto.ListStagingSessionsResponse body = response.getBody();
        log.info("Listed staging sessions for CashFlow {}: {} sessions, hasPendingImport={}",
                cashFlowId, body.getStagingSessions().size(), body.isHasPendingImport());
        return body;
    }

    /**
     * Stages transactions for import.
     */
    public BankDataIngestionDto.StageTransactionsResponse stageTransactions(
            String cashFlowId,
            List<BankDataIngestionDto.BankTransactionJson> transactions) {

        BankDataIngestionDto.StageTransactionsRequest request = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(transactions)
                .build();

        ResponseEntity<BankDataIngestionDto.StageTransactionsResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-ingestion/" + cashFlowId + "/staging",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                BankDataIngestionDto.StageTransactionsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BankDataIngestionDto.StageTransactionsResponse body = response.getBody();
        log.info("Staged {} transactions for CashFlow {}, status: {}",
                transactions.size(), cashFlowId, body.getStatus());
        return body;
    }

    /**
     * Creates a bank transaction for staging.
     */
    public BankDataIngestionDto.BankTransactionJson bankTransaction(String bankTransactionId, String name,
                                                                     String bankCategory, double amount,
                                                                     String currency, Type type,
                                                                     ZonedDateTime paidDate) {
        return BankDataIngestionDto.BankTransactionJson.builder()
                .bankTransactionId(bankTransactionId)
                .name(name)
                .description("Auto-generated transaction")
                .bankCategory(bankCategory)
                .amount(amount)
                .currency(currency)
                .type(type)
                .paidDate(paidDate)
                .build();
    }

    /**
     * Starts an import job for staged transactions.
     */
    public BankDataIngestionDto.StartImportResponse startImport(String cashFlowId, String stagingSessionId) {
        BankDataIngestionDto.StartImportRequest request = BankDataIngestionDto.StartImportRequest.builder()
                .stagingSessionId(stagingSessionId)
                .build();

        ResponseEntity<BankDataIngestionDto.StartImportResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-ingestion/" + cashFlowId + "/import",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                BankDataIngestionDto.StartImportResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BankDataIngestionDto.StartImportResponse body = response.getBody();
        log.info("Started import for CashFlow {}, status: {}", cashFlowId, body.getStatus());
        return body;
    }

    /**
     * Gets import job progress.
     */
    public BankDataIngestionDto.GetImportProgressResponse getImportProgress(String cashFlowId, String jobId) {
        ResponseEntity<BankDataIngestionDto.GetImportProgressResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/v1/bank-data-ingestion/" + cashFlowId + "/import/" + jobId,
                BankDataIngestionDto.GetImportProgressResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Finalizes an import job.
     */
    public BankDataIngestionDto.FinalizeImportResponse finalizeImport(String cashFlowId, String jobId,
                                                                       boolean deleteMappings) {
        BankDataIngestionDto.FinalizeImportRequest request = BankDataIngestionDto.FinalizeImportRequest.builder()
                .deleteMappings(deleteMappings)
                .build();

        ResponseEntity<BankDataIngestionDto.FinalizeImportResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-ingestion/" + cashFlowId + "/import/" + jobId + "/finalize",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                BankDataIngestionDto.FinalizeImportResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Finalized import job {} for CashFlow {}", jobId, cashFlowId);
        return response.getBody();
    }

    /**
     * Gets staging preview.
     */
    public BankDataIngestionDto.GetStagingPreviewResponse getStagingPreview(String cashFlowId, String stagingSessionId) {
        ResponseEntity<BankDataIngestionDto.GetStagingPreviewResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/v1/bank-data-ingestion/" + cashFlowId + "/staging/" + stagingSessionId,
                BankDataIngestionDto.GetStagingPreviewResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Deletes a staging session.
     */
    public void deleteStagingSession(String cashFlowId, String stagingSessionId) {
        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-ingestion/" + cashFlowId + "/staging/" + stagingSessionId,
                HttpMethod.DELETE,
                null,
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Deleted staging session {} for CashFlow {}", stagingSessionId, cashFlowId);
    }

    // ============ Helper Methods ============

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
