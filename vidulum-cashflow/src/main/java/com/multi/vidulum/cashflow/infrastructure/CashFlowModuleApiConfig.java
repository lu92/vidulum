package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Which transport answers {@link CashFlowModuleApi}.
 *
 * <p>One property, two values, and the whole point is that nothing but wiring changes between them:
 *
 * <pre>
 * vidulum.cashflow.api.transport: in-process   # the default - CashFlowModuleApiImpl
 *                                http          # HttpCashFlowModuleApi against base-url
 * </pre>
 *
 * <p><b>Exactly one is ever a bean.</b> This factory is conditional on {@code http} and
 * {@code CashFlowModuleApiImpl} on {@code in-process} with {@code matchIfMissing}, so the two
 * cannot coexist and an unset property gives the local path.
 * {@code BootstrapWiresEveryModuleTest} asserts the count, which is what makes that a guarantee
 * rather than a convention.
 *
 * <p><b>In-process is the default deliberately, and it is a change.</b> Before this,
 * {@code bank_data_ingestion}'s own client had {@code matchIfMissing = true} on
 * {@code vidulum.cashflow-service.enabled} and nothing set that property - so the running
 * application was calling its own REST endpoints over {@code localhost} to create categories,
 * once per category, inside one JVM. Every integration test switched it off and substituted a
 * double, which is why nobody noticed.
 */
@Configuration
public class CashFlowModuleApiConfig {

    @Value("${vidulum.cashflow.api.base-url:http://localhost:8080}")
    private String baseUrl;

    @Bean
    @ConditionalOnProperty(name = "vidulum.cashflow.api.transport", havingValue = "http")
    public CashFlowModuleApi httpCashFlowModuleApi(RestClient.Builder restClientBuilder) {
        RestClient restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    // The endpoints behind this are .authenticated(), and this adapter has no
                    // credentials of its own - it forwards the caller's. Which also says where the
                    // limit is: on a thread with no request context there is no header to forward,
                    // so a scheduler or a Kafka listener on this transport gets 403. Same mechanism
                    // as CashFlowServiceClientConfig, duplicated rather than hoisted while it has
                    // two users; a third makes it worth extracting.
                    String authHeader = incomingAuthorizationHeader();
                    if (authHeader != null) {
                        request.getHeaders().add("Authorization", authHeader);
                    }
                    return execution.execute(request, body);
                })
                .build();

        return new HttpCashFlowModuleApi(restClient);
    }

    private static String incomingAuthorizationHeader() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getHeader("Authorization");
        }
        return null;
    }
}
