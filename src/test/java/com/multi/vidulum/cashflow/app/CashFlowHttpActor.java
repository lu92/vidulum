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

    // ============ Helper Methods ============

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
