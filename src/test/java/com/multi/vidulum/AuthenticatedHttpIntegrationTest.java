package com.multi.vidulum;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.app.TestCashFlowServiceClient;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.security.auth.AuthenticationResponse;
import com.multi.vidulum.security.auth.RegisterRequest;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base class for HTTP integration tests WITH SECURITY ENABLED.
 *
 * Unlike AbstractHttpIntegrationTest which disables security, this class:
 * - Keeps Spring Security ENABLED (real JWT authentication)
 * - Provides helper methods for registration and authentication
 * - Stores JWT tokens for authenticated requests
 *
 * This ensures tests verify the full security flow, catching bugs like
 * the isTokenValid() bug found during Spring Boot 3.5.2 upgrade.
 *
 * Usage:
 * 1. Extend this class
 * 2. Call registerAndAuthenticate() in @BeforeEach
 * 3. Pass accessToken to actors via setJwtToken()
 */
@Slf4j
@SpringBootTest(
        classes = {FixedClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import({PortfolioAppConfig.class, TradingAppConfig.class, AuthenticatedHttpIntegrationTest.TestCashFlowServiceClientConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
public abstract class AuthenticatedHttpIntegrationTest {

    /**
     * Test configuration that provides CashFlowServiceClient using direct gateway calls.
     * Uses @Lazy on CommandGateway to break circular dependency.
     */
    @TestConfiguration
    static class TestCashFlowServiceClientConfig {
        @Bean
        public CashFlowServiceClient cashFlowServiceClient(
                QueryGateway queryGateway,
                @Lazy CommandGateway commandGateway) {
            return new TestCashFlowServiceClient(queryGateway, commandGateway);
        }
    }

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

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Disable HTTP client for CashFlowServiceClient - use direct repository access instead
        registry.add("vidulum.cashflow-service.enabled", () -> "false");
        // IMPORTANT: Security stays ENABLED (default) - no app.security.enabled=false
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    // JWT token storage
    protected String accessToken;
    protected String refreshToken;
    protected String userId;

    /**
     * Registers a new user and stores JWT tokens for authenticated requests.
     * Call this in @BeforeEach to set up authentication for the test.
     *
     * @param username unique username
     * @param email unique email
     * @param password password (must meet validation requirements)
     */
    protected void registerAndAuthenticate(String username, String email, String password) {
        RegisterRequest request = RegisterRequest.builder()
                .username(username)
                .email(email)
                .password(password)
                .build();

        ResponseEntity<AuthenticationResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register",
                request,
                AuthenticationResponse.class
        );

        assertThat(response.getStatusCode())
                .as("Registration should succeed for user %s", username)
                .isEqualTo(HttpStatus.OK);

        AuthenticationResponse authResponse = response.getBody();
        assertThat(authResponse).isNotNull();

        this.accessToken = authResponse.getAccessToken();
        this.refreshToken = authResponse.getRefreshToken();
        this.userId = authResponse.getUserId();

        log.info("Registered and authenticated user: username={}, userId={}", username, userId);
    }

    /**
     * Generates a unique username for test isolation.
     */
    protected String uniqueUsername() {
        return "testuser_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Convenience method to register with auto-generated credentials.
     */
    protected void registerAndAuthenticate() {
        String username = uniqueUsername();
        registerAndAuthenticate(username, username + "@test.com", "SecurePassword123!");
    }

    /**
     * Creates HTTP headers with JWT Bearer token for authenticated requests.
     */
    protected HttpHeaders authenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    /**
     * Creates HTTP headers without authentication (for testing 401 responses).
     */
    protected HttpHeaders unauthenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Creates HTTP headers with an invalid JWT token (for testing 401 responses).
     */
    protected HttpHeaders invalidTokenHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("invalid.jwt.token");
        return headers;
    }

    /**
     * Waits for Kafka listeners to be assigned partitions.
     * Call this if your test depends on Kafka event processing.
     */
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
