package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.user_financial_profile.api.UserFinancialProfileApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Configuration for HTTP service clients used by bank-data-ingestion.
 *
 * Provides HTTP implementations for:
 * - CashFlowServiceClient (cashflow-service REST API)
 * - UserFinancialProfileApi (user-financial-profile REST API via @HttpExchange proxy)
 *
 * Configuration properties:
 * - vidulum.cashflow-service.base-url: Base URL (default: http://localhost:8080)
 * - vidulum.cashflow-service.enabled: Set to false to disable HTTP clients (integration tests)
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

    /**
     * HTTP proxy for user-financial-profile REST API.
     * Generated from {@link UserFinancialProfileApi} @HttpExchange interface.
     * Propagates Authorization header from incoming request context.
     *
     * Disabled when vidulum.cashflow-service.enabled=false (integration tests provide their own).
     */
    @Bean
    @ConditionalOnProperty(
            name = "vidulum.cashflow-service.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public UserFinancialProfileApi userFinancialProfileApi(RestClient.Builder restClientBuilder) {
        RestClient restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    String authHeader = extractAuthorizationHeader();
                    if (authHeader != null) {
                        request.getHeaders().add("Authorization", authHeader);
                    }
                    return execution.execute(request, body);
                })
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(UserFinancialProfileApi.class);
    }

    private String extractAuthorizationHeader() {
        try {
            var attributes = (org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                return attributes.getRequest().getHeader("Authorization");
            }
        } catch (Exception e) {
            // No request context available
        }
        return null;
    }
}
