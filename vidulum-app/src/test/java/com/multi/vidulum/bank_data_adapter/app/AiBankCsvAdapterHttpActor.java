package com.multi.vidulum.bank_data_adapter.app;

import com.multi.vidulum.common.error.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actor for HTTP-based integration tests of bank-data-adapter module.
 * Encapsulates REST API calls to AI CSV transformation endpoints.
 *
 * This follows the same pattern as BankDataIngestionHttpActor for consistency.
 */
@Slf4j
public class AiBankCsvAdapterHttpActor {

    private final TestRestTemplate restTemplate;
    private final String baseUrl;
    private String jwtToken;

    public AiBankCsvAdapterHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
    }

    /**
     * Sets the JWT token for authenticated requests.
     */
    public void setJwtToken(String token) {
        this.jwtToken = token;
        log.debug("JWT token set for AiBankCsvAdapterHttpActor");
    }

    // ============ Transform Operations ============

    /**
     * Transforms a CSV file using AI.
     *
     * @param resourcePath path to CSV file in classpath
     * @param bankHint optional hint about the bank
     * @return transformation response
     */
    public AiBankCsvAdapterDto.TransformResponse transform(String resourcePath, String bankHint) {
        Resource csvResource = new ClassPathResource(resourcePath);

        byte[] fileContent;
        try (InputStream is = csvResource.getInputStream()) {
            fileContent = is.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV file from classpath: " + resourcePath, e);
        }

        return transformContent(resourcePath.substring(resourcePath.lastIndexOf('/') + 1), fileContent, bankHint);
    }

    /**
     * Transforms CSV content using AI.
     */
    public AiBankCsvAdapterDto.TransformResponse transformContent(String fileName, byte[] csvContent, String bankHint) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (jwtToken != null) {
            headers.setBearerAuth(jwtToken);
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(csvContent, createFileHeaders(fileName)));
        if (bankHint != null && !bankHint.isBlank()) {
            body.add("bankHint", bankHint);
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<AiBankCsvAdapterDto.TransformResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/transform",
                HttpMethod.POST,
                requestEntity,
                AiBankCsvAdapterDto.TransformResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AiBankCsvAdapterDto.TransformResponse result = response.getBody();
        log.info("Transformed CSV '{}': id={}, bank={}, rows={}",
                fileName, result.getTransformationId(), result.getDetectedBank(), result.getRowCount());
        return result;
    }

    /**
     * Gets a transformation by ID.
     */
    public AiBankCsvAdapterDto.TransformResponse getTransformation(String transformationId) {
        ResponseEntity<AiBankCsvAdapterDto.TransformResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/" + transformationId,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                AiBankCsvAdapterDto.TransformResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Gets transformation preview (first N rows).
     */
    public AiBankCsvAdapterDto.PreviewResponse getPreview(String transformationId, int rows) {
        ResponseEntity<AiBankCsvAdapterDto.PreviewResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/" + transformationId + "/preview?rows=" + rows,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                AiBankCsvAdapterDto.PreviewResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Downloads the transformed CSV.
     */
    public String downloadCsv(String transformationId) {
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/" + transformationId + "/download",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Imports transformation to a CashFlow.
     */
    public AiBankCsvAdapterDto.ImportResponse importToCashFlow(String transformationId, String cashFlowId) {
        AiBankCsvAdapterDto.ImportRequest request = AiBankCsvAdapterDto.ImportRequest.builder()
                .cashFlowId(cashFlowId)
                .build();

        ResponseEntity<AiBankCsvAdapterDto.ImportResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/" + transformationId + "/import",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                AiBankCsvAdapterDto.ImportResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AiBankCsvAdapterDto.ImportResponse result = response.getBody();
        log.info("Imported transformation {} to CashFlow {}: stagingSession={}",
                transformationId, cashFlowId, result.getStagingSessionId());
        return result;
    }

    /**
     * Gets transformation history for current user.
     */
    public List<AiBankCsvAdapterDto.TransformHistoryItem> getHistory() {
        ResponseEntity<AiBankCsvAdapterDto.HistoryResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/history",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                AiBankCsvAdapterDto.HistoryResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().getTransformations();
    }

    // ============ Mapping Rules Cache Operations ============

    /**
     * Gets cached mapping rules for a bank.
     */
    public AiBankCsvAdapterDto.MappingRulesResponse getMappingRules(String bankIdentifier) {
        ResponseEntity<AiBankCsvAdapterDto.MappingRulesResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/mapping-rules/" + bankIdentifier,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                AiBankCsvAdapterDto.MappingRulesResponse.class
        );

        if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
            return null;
        }
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Lists all cached mapping rules.
     */
    public List<AiBankCsvAdapterDto.MappingRulesSummary> listMappingRules() {
        ResponseEntity<AiBankCsvAdapterDto.ListMappingRulesResponse> response = restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/mapping-rules",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                AiBankCsvAdapterDto.ListMappingRulesResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().getRules();
    }

    // ============ Error-Expecting Operations ============

    /**
     * Transforms expecting an error response.
     */
    public ResponseEntity<ApiError> transformExpectingError(String fileName, byte[] csvContent, String bankHint) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (jwtToken != null) {
            headers.setBearerAuth(jwtToken);
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(csvContent, createFileHeaders(fileName)));
        if (bankHint != null && !bankHint.isBlank()) {
            body.add("bankHint", bankHint);
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        return restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/transform",
                HttpMethod.POST,
                requestEntity,
                ApiError.class
        );
    }

    /**
     * Gets transformation expecting an error response.
     */
    public ResponseEntity<ApiError> getTransformationExpectingError(String transformationId) {
        return restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/" + transformationId,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                ApiError.class
        );
    }

    /**
     * Imports transformation expecting an error response.
     */
    public ResponseEntity<ApiError> importExpectingError(String transformationId, String cashFlowId) {
        AiBankCsvAdapterDto.ImportRequest request = AiBankCsvAdapterDto.ImportRequest.builder()
                .cashFlowId(cashFlowId)
                .build();

        return restTemplate.exchange(
                baseUrl + "/api/v1/bank-data-adapter/" + transformationId + "/import",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                ApiError.class
        );
    }

    // ============ Helper Methods ============

    private HttpHeaders createFileHeaders(String fileName) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
        fileHeaders.setContentDispositionFormData("file", fileName);
        return fileHeaders;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwtToken != null) {
            headers.setBearerAuth(jwtToken);
        }
        return headers;
    }
}
