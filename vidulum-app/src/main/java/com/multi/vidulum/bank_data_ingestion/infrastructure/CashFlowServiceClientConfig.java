package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for CashFlowServiceClient.
 *
 * Uses HttpCashFlowServiceClient which communicates with cashflow-service via REST API.
 * This enables bank-data-ingestion to run as a separate microservice.
 *
 * Configuration properties:
 * - vidulum.cashflow-service.base-url: Base URL for cashflow-service (default: http://localhost:8080)
 * - vidulum.cashflow-service.connect-timeout-ms: Connection timeout (default: 5000)
 * - vidulum.cashflow-service.read-timeout-ms: Read timeout (default: 30000)
 */
@Configuration
public class CashFlowServiceClientConfig {

    @Value("${vidulum.cashflow-service.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${vidulum.cashflow-service.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${vidulum.cashflow-service.read-timeout-ms:30000}")
    private long readTimeoutMs;

    /**
     * HTTP implementation for microservice architecture.
     * Communicates with cashflow-service via REST API.
     *
     * Disabled when vidulum.cashflow-service.enabled=false (used in integration tests).
     */
    @Bean
    @ConditionalOnProperty(
            name = "vidulum.cashflow-service.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public CashFlowServiceClient cashFlowServiceClient(RestClient.Builder restClientBuilder) {
        RestClient.Builder configuredBuilder = restClientBuilder
                .defaultHeaders(headers -> {
                    headers.set("Content-Type", "application/json");
                    headers.set("Accept", "application/json");
                });

        return new HttpCashFlowServiceClient(configuredBuilder, baseUrl);
    }
}
