package com.multi.vidulum.bank_data_adapter.app;

import com.multi.vidulum.bank_data_adapter.rest.UnifiedCsvImportController;
import com.multi.vidulum.common.error.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * HTTP Actor for Unified CSV Import Controller tests.
 * Encapsulates all HTTP/REST interactions with the UnifiedCsvImportController.
 */
@Slf4j
public class UnifiedCsvImportHttpActor {

    private final TestRestTemplate restTemplate;
    private final int port;
    private String jwtToken;

    public UnifiedCsvImportHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.port = port;
    }

    public void setJwtToken(String jwtToken) {
        this.jwtToken = jwtToken;
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/csv-import";
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (jwtToken != null) {
            headers.setBearerAuth(jwtToken);
        }
        return headers;
    }

    /**
     * Upload CSV file and get transformation result.
     */
    public UnifiedCsvImportController.UploadResponse upload(byte[] csvContent, String fileName, String bankHint) {
        HttpHeaders headers = createHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csvContent) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (bankHint != null) {
            body.add("bankHint", bankHint);
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<UnifiedCsvImportController.UploadResponse> response = restTemplate.exchange(
            baseUrl() + "/upload",
            HttpMethod.POST,
            requestEntity,
            UnifiedCsvImportController.UploadResponse.class
        );

        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("Upload successful: id={}, detection={}, rows={}",
                response.getBody().transformationId(),
                response.getBody().detectionResult(),
                response.getBody().rowCount());
        }

        return response.getBody();
    }

    /**
     * Upload CSV file expecting an error response.
     */
    public ResponseEntity<ApiError> uploadExpectingError(byte[] csvContent, String fileName, String bankHint) {
        HttpHeaders headers = createHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csvContent) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (bankHint != null) {
            body.add("bankHint", bankHint);
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        return restTemplate.exchange(
            baseUrl() + "/upload",
            HttpMethod.POST,
            requestEntity,
            ApiError.class
        );
    }

    // ========== Helper methods for creating test data ==========

    /**
     * Creates canonical format CSV content.
     */
    public static String createCanonicalCsv(String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber\n");
        for (String row : rows) {
            sb.append(row).append("\n");
        }
        return sb.toString();
    }

    /**
     * Creates a canonical CSV row.
     */
    public static String canonicalRow(String txnId, String name, String category,
                                       double amount, String currency, String type, String date) {
        // Use Locale.US to ensure decimal point (not comma) in amount
        return String.format(java.util.Locale.US, "%s,%s,,%s,%.2f,%s,%s,%s,%s,,",
            txnId, name, category, amount, currency, type, date, date);
    }
}
