package com.multi.vidulum;

import com.multi.vidulum.shared.cqrs.CqrsGatewayConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Test-only Spring Boot application for vidulum-cashflow integration tests.
 * Not included in production JAR.
 *
 * <p>The CQRS gateways come from the same configuration the production application imports, so
 * these tests cannot drift from how the gateways are assembled at runtime.</p>
 */
@SpringBootApplication
@Import(CqrsGatewayConfiguration.class)
public class CashFlowTestApplication {
}
