package com.multi.vidulum.bank_data_adapter.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.multi.vidulum.bank_data_adapter.domain.exceptions.IngestionServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;

/**
 * REST client for communication with bank-data-ingestion module.
 */
@Slf4j
@Component
public class BankDataIngestionClient {

    private final RestClient restClient;
    private final String baseUrl;

    public BankDataIngestionClient(
            RestClient.Builder restClientBuilder,
            @Value("${bank-data-ingestion.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = baseUrl;
    }

    /**
     * Send transformed CSV to bank-data-ingestion for staging.
     *
     * @param cashFlowId       CashFlow ID to import to
     * @param csvContent       Transformed CSV content (BankCsvRow format)
     * @param fileName         File name for the upload
     * @param authToken        JWT token for authentication
     * @param metadata         Optional metadata from AI transformation
     * @return Upload response with staging session ID
     */
    public UploadCsvResponse sendToIngestion(
            String cashFlowId,
            String csvContent,
            String fileName,
            String authToken,
            TransformationMetadata metadata) {
        log.info("Sending transformed CSV to ingestion: cashFlowId={}, fileName={}, size={}, metadata={}",
            cashFlowId, fileName, csvContent.length(), metadata != null);

        try {
            // Create multipart request
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new NamedByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8), fileName));

            // Add metadata fields if present
            if (metadata != null) {
                if (metadata.transformationId() != null) {
                    body.add("transformationId", metadata.transformationId());
                }
                if (metadata.detectedLanguage() != null) {
                    body.add("detectedLanguage", metadata.detectedLanguage());
                }
                if (metadata.detectedBank() != null) {
                    body.add("detectedBank", metadata.detectedBank());
                }
                if (metadata.detectedCountry() != null) {
                    body.add("detectedCountry", metadata.detectedCountry());
                }
                if (metadata.originalFileName() != null) {
                    body.add("originalFileName", metadata.originalFileName());
                }
                if (metadata.userId() != null) {
                    body.add("userId", metadata.userId());
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(authToken.replace("Bearer ", ""));

            String url = baseUrl + "/api/v1/bank-data-ingestion/cf=" + cashFlowId + "/upload";

            ResponseEntity<UploadCsvResponse> response = restClient.post()
                .uri(url)
                .headers(h -> h.addAll(headers))
                .body(body)
                .retrieve()
                .toEntity(UploadCsvResponse.class);

            log.info("Ingestion upload successful: stagingSessionId={}",
                response.getBody() != null ? response.getBody().getStagingSessionId() : "null");

            return response.getBody();

        } catch (HttpClientErrorException e) {
            log.error("Client error from ingestion service: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IngestionServiceException(
                "Ingestion service error: " + e.getMessage(),
                e.getStatusCode().value(),
                extractErrorCode(e.getResponseBodyAsString())
            );
        } catch (HttpServerErrorException e) {
            log.error("Server error from ingestion service: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IngestionServiceException(
                "Ingestion service unavailable: " + e.getMessage(),
                e.getStatusCode().value(),
                "SERVER_ERROR"
            );
        } catch (RestClientException e) {
            log.error("Error connecting to ingestion service", e);
            throw new IngestionServiceException("Cannot connect to ingestion service", e);
        }
    }

    private String extractErrorCode(String responseBody) {
        // Try to extract error code from JSON response
        if (responseBody != null && responseBody.contains("errorCode")) {
            int start = responseBody.indexOf("\"errorCode\":\"") + 13;
            int end = responseBody.indexOf("\"", start);
            if (start > 12 && end > start) {
                return responseBody.substring(start, end);
            }
        }
        return "UNKNOWN";
    }

    /**
     * Response from upload CSV endpoint.
     * Matches BankDataIngestionDto.UploadCsvResponse structure.
     * Uses @JsonIgnoreProperties to handle extra fields from backend.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UploadCsvResponse(
        ParseSummary parseSummary,
        StagingResult stagingResult  // Field name must match JSON: "stagingResult"
    ) {
        /**
         * Helper method to extract stagingSessionId from nested stagingResult.
         */
        public String getStagingSessionId() {
            return stagingResult != null ? stagingResult.stagingSessionId() : null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParseSummary(
        int totalRows,
        int successfulRows,
        int failedRows
    ) {}

    /**
     * Staging result from bank-data-ingestion.
     * Matches BankDataIngestionDto.StageTransactionsResponse structure.
     * Only maps the fields we need; ignores extra fields like expiresAt, summary, etc.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StagingResult(
        String stagingSessionId,
        String cashFlowId,
        String status
    ) {}

    /**
     * Backwards-compatible method without metadata.
     */
    public UploadCsvResponse sendToIngestion(String cashFlowId, String csvContent, String fileName, String authToken) {
        return sendToIngestion(cashFlowId, csvContent, fileName, authToken, null);
    }

    /**
     * Metadata from AI transformation to be passed to staging session.
     */
    public record TransformationMetadata(
            String transformationId,
            String detectedLanguage,
            String detectedBank,
            String detectedCountry,
            String originalFileName,
            String userId
    ) {}

    /**
     * ByteArrayResource with a filename (required for multipart uploads).
     */
    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        public NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
