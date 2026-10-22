package com.multi.vidulum.bank_data_ingestion.staging.infrastructure;

import com.multi.vidulum.user_financial_profile.api.UserFinancialProfileApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * The one HTTP client this module still needs: the proxy for {@link UserFinancialProfileApi}.
 *
 * <p>It was {@code CashFlowServiceClientConfig} and built two clients. The other one is gone with
 * the port it implemented - everything staging asked cashflow for is now on
 * {@code CashFlowModuleApi} and {@code CashFlowForecastApi}, answered in process. The profile is
 * different in kind: {@code UserFinancialProfileApi} is declared with {@code @HttpExchange}, so the
 * proxy <i>is</i> the implementation rather than one of two transports.
 *
 * <p><b>The property keys still say {@code vidulum.cashflow-service.*}</b>, which no longer
 * describes what they switch. Left alone on purpose: three test base classes set
 * {@code .enabled=false} to substitute their own profile, and renaming a key is a change to each of
 * them plus the production yml - worth doing, worth doing separately.
 *
 * <p>And {@code matchIfMissing = true} below is the shape CLAUDE.md warns about. It is survivable
 * here only because nothing in process implements this interface except the controller itself; the
 * day one does, this default is the bug.
 */
@Configuration
public class UserFinancialProfileClientConfig {

    @Value("${vidulum.cashflow-service.base-url:http://localhost:8080}")
    private String baseUrl;

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
