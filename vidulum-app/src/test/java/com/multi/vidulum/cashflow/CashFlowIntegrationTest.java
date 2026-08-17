package com.multi.vidulum.cashflow;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.app.TestCashFlowServiceClient;
import com.multi.vidulum.cashflow.domain.CashFlowEventEmitter;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.infrastructure.CashFlowMongoRepository;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatementRepository;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastMongoRepository;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.config.TestAiConfig;
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
 * Self-contained integration test base class for all cashflow-related tests.
 * Covers: cashflow, cashflow_forecast_processor, bank_data_ingestion,
 * bank_data_adapter, and recurring_rules.
 *
 * <p>Provides:
 * <ul>
 *   <li>MongoDB + Kafka testcontainers</li>
 *   <li>Web server (RANDOM_PORT) + TestRestTemplate</li>
 *   <li>JWT authentication helpers</li>
 *   <li>Cashflow-specific repositories and helpers</li>
 *   <li>TestCashFlowServiceClient for bank_data_ingestion tests</li>
 * </ul>
 *
 * <p>Fully independent from {@code IntegrationTest} — when these packages
 * are extracted to a separate Maven module, this class moves with them.</p>
 */
@Slf4j
@SpringBootTest(
        classes = {FixedClockConfig.class, TestAiConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import({PortfolioAppConfig.class, TradingAppConfig.class, CashFlowIntegrationTest.TestCashFlowServiceClientConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
public abstract class CashFlowIntegrationTest {

    @TestConfiguration
    static class TestCashFlowServiceClientConfig {
        @Bean
        public CashFlowServiceClient cashFlowServiceClient(
                QueryGateway queryGateway,
                @Lazy CommandGateway commandGateway) {
            return new TestCashFlowServiceClient(queryGateway, commandGateway);
        }
    }

    // Shared reusable containers
    protected static final MongoDBContainer mongoDBContainer;
    protected static final KafkaContainer kafka;

    static {
        mongoDBContainer = new MongoDBContainer("mongo:8.0.4");
        mongoDBContainer.start();

        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));
        kafka.start();

        log.info("CashFlowIntegrationTest - Testcontainers started - MongoDB: {}, Kafka: {}",
                mongoDBContainer.getReplicaSetUrl(), kafka.getBootstrapServers());
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("vidulum.cashflow-service.enabled", () -> "false");
    }

    // --- Web & HTTP infrastructure ---

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    // --- JWT authentication ---

    protected String accessToken;
    protected String refreshToken;
    protected String userId;

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

    protected void registerAndAuthenticate() {
        String username = uniqueUsername();
        registerAndAuthenticate(username, username + "@test.com", "SecurePassword123!");
    }

    protected String uniqueUsername() {
        return "testuser_" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected HttpHeaders authenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    protected HttpHeaders unauthenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected HttpHeaders invalidTokenHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("invalid.jwt.token");
        return headers;
    }

    // --- Kafka helpers ---

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

    // --- Cashflow-specific repositories ---

    @Autowired
    protected DomainCashFlowRepository domainCashFlowRepository;

    @Autowired
    protected CashFlowMongoRepository cashFlowMongoRepository;

    @Autowired
    protected CashFlowForecastMongoRepository cashFlowForecastMongoRepository;

    @Autowired
    protected CashFlowForecastStatementRepository statementRepository;

    @Autowired
    protected CashFlowEventEmitter cashFlowEventEmitter;

    protected boolean lastEventIsProcessed(CashFlowId cashFlowId, Checksum lastEventChecksum) {
        return statementRepository.findByCashFlowId(cashFlowId)
                .map(statement -> statement.getLastMessageChecksum().equals(lastEventChecksum))
                .orElse(false);
    }
}
