package com.multi.vidulum;

import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.shared.cqrs.CqrsGatewayConfiguration;
import com.multi.vidulum.trading.app.TradingAppConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Test-only Spring Boot application for the cross-module tests in vidulum-wealth-tests.
 * Not included in production JAR.
 */
@SpringBootApplication
@Import({PortfolioAppConfig.class, TradingAppConfig.class, CqrsGatewayConfiguration.class})
public class WealthTestApplication {

    /**
     * Overrides {@code SecurityContextUserProvider}, which reads an authentication these tests
     * never create — see {@link TestAuthenticatedUser}.
     */
    @Bean
    @Primary
    public TestAuthenticatedUser testAuthenticatedUser() {
        return new TestAuthenticatedUser();
    }
}
