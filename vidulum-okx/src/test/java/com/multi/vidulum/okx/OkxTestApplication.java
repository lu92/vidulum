package com.multi.vidulum.okx;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only application for the OKX module. Not part of the production jar.
 *
 * <p>Component scanning is deliberately narrowed to this module plus {@code quotation} from
 * vidulum-wealth. That package is self-contained - its only import from outside is
 * {@code UserCreatedEvent} - so the context needs Kafka but not MongoDB, which keeps the test to
 * a single container. Scanning all of {@code com.multi.vidulum} would drag in the portfolio and
 * trading repositories and force a Mongo container for no benefit.
 */
@SpringBootApplication(scanBasePackages = {"com.multi.vidulum.okx", "com.multi.vidulum.quotation"})
public class OkxTestApplication {

    /**
     * Opens every endpoint for the duration of these tests.
     *
     * <p>Spring Security is on the classpath transitively and its default auto-configuration
     * answers 401 to the quotation endpoints - which is correct production behaviour and matches
     * {@code SecurityConfiguration}, where only {@code /api/v1/auth/**} and
     * {@code /actuator/health} are public. These tests verify that the OKX provider is wired into
     * the quotation pipeline, not who may call it, so authentication would only add noise.
     */
    /**
     * Required by {@code ExchangeStatusRestController}, which this context picks up along with
     * the rest of {@code com.multi.vidulum.quotation}. Fixed, so a status timestamp is
     * assertable; production takes its clock from {@code VidulumApplication}.
     */
    @Bean
    Clock clock() {
        return Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    @Bean
    SecurityFilterChain permitAllForTests(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
