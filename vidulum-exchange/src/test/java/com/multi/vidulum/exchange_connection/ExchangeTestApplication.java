package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapter;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.multi.vidulum.security.config.ErrorHttpHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;
import java.util.List;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Test-only application for this module: MongoDB, a web layer, no Kafka. The module's whole
 * dependency surface is shared-kernel plus Spring Data Mongo, so the context starts in seconds.
 *
 * <p>{@code ErrorHttpHandler} is imported by class rather than scanned. It lives in
 * {@code com.multi.vidulum.security.config}, which the real application picks up because
 * {@code VidulumApplication} scans all of {@code com.multi.vidulum}; scanning that package here
 * would also drag in {@code SecurityConfiguration} and the JWT machinery, which these tests do
 * not need. Without it every business exception would answer 500 instead of its own status.
 */
@SpringBootApplication(scanBasePackages = "com.multi.vidulum.exchange_connection")
@Import(ErrorHttpHandler.class)
public class ExchangeTestApplication {

    /**
     * Gateways are declared by {@code VidulumApplication} in production, which this test
     * application does not scan — so they are rebuilt here the same way, by registering every
     * handler the context found.
     */
    @Bean
    CommandGateway commandGateway(List<CommandHandler<?, ?>> commandHandlers) {
        CommandGateway commandGateway = new CommandGateway();
        commandHandlers.forEach(commandGateway::registerCommandHandler);
        return commandGateway;
    }

    @Bean
    QueryGateway queryGateway(List<QueryHandler<?, ?>> queryHandlers) {
        QueryGateway queryGateway = new QueryGateway();
        queryHandlers.forEach(queryGateway::registerQueryHandler);
        return queryGateway;
    }

    /** Fixed, so {@code createdAt} and the two sync timestamps can be asserted exactly. */
    @Bean
    Clock clock() {
        return Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    /**
     * Stands in for an exchange's module, which this module must not depend on.
     */
    @Bean
    ExchangeAdapter stubExchangeAdapter() {
        return new StubExchangeAdapter();
    }

    /**
     * The real provider reads the JWT subject; here the caller is switched directly so that
     * ownership rules can be exercised without minting tokens. Authentication itself is covered
     * in vidulum-app.
     */
    @Bean
    AuthenticatedUserProvider authenticatedUserProvider() {
        return () -> CurrentTestUser.get();
    }

    /**
     * Opens every endpoint. Production behaviour is {@code anyRequest().authenticated()} from
     * {@code SecurityConfiguration}; these tests are about what the endpoints do, not who may
     * call them.
     *
     * <p>Conditional on a servlet context because the persistence tests run this same
     * application with no web layer, where {@code HttpSecurity} does not exist.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain permitAllForTests(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /** Which user the stubbed provider reports. Switched per test. */
    static final class CurrentTestUser {
        private static volatile UserId current = UserId.of("U10000001");

        static UserId get() {
            return current;
        }

        static void set(UserId userId) {
            current = userId;
        }
    }
}
