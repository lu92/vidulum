package com.multi.vidulum;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.app.TestCashFlowServiceClient;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.trading.app.TradingAppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Abstract base class for HTTP integration tests.
 *
 * Provides shared Testcontainers (MongoDB and Kafka) that are started once
 * and reused across all tests, avoiding the issues with Spring Boot 3.5.2
 * graceful shutdown and container lifecycle management.
 *
 * Usage:
 * - Extend this class for HTTP integration tests
 * - Override setUp() method for test-specific initialization
 * - Use restTemplate and port for HTTP calls
 */
@Slf4j
@SpringBootTest(
        classes = {FixedClockConfig.class, AbstractHttpIntegrationTest.TestSecurityConfig.class,
                AbstractHttpIntegrationTest.TestCashFlowServiceClientConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import({PortfolioAppConfig.class, TradingAppConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
public abstract class AbstractHttpIntegrationTest {

    // Shared reusable containers - started once and reused across all tests
    protected static final MongoDBContainer mongoDBContainer;
    protected static final KafkaContainer kafka;

    static {
        // Initialize MongoDB container
        mongoDBContainer = new MongoDBContainer("mongo:8.0");
        mongoDBContainer.start();

        // Initialize Kafka container
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));
        kafka.start();

        log.info("Testcontainers started - MongoDB: {}, Kafka: {}",
                mongoDBContainer.getReplicaSetUrl(), kafka.getBootstrapServers());
    }

    @TestConfiguration
    public static class TestSecurityConfig {
        @Bean
        @Order(1)
        public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http
                    .securityMatcher("/**")
                    .authorizeHttpRequests(req -> req.anyRequest().permitAll())
                    .csrf(AbstractHttpConfigurer::disable);
            return http.build();
        }
    }

    @TestConfiguration
    public static class TestCashFlowServiceClientConfig {
        @Bean
        public CashFlowServiceClient cashFlowServiceClient(
                QueryGateway queryGateway,
                @Lazy CommandGateway commandGateway) {
            return new TestCashFlowServiceClient(queryGateway, commandGateway);
        }
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Disable HTTP client for CashFlowServiceClient - use direct repository access instead
        registry.add("vidulum.cashflow-service.enabled", () -> "false");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    protected void waitForKafkaListeners() {
        kafkaListenerEndpointRegistry.getListenerContainers().forEach(container -> {
            if (container.isRunning()) {
                try {
                    ContainerTestUtils.waitForAssignment(container, 1);
                } catch (IllegalStateException e) {
                    log.debug("Skipping partition wait for container: {}", e.getMessage());
                }
            }
        });
    }
}
