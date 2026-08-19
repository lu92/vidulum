package com.multi.vidulum;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.common.*;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.config.TestAiConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.quotation.app.QuoteRestController;
import com.multi.vidulum.task.TaskRestController;
import com.multi.vidulum.security.auth.AuthenticationController;
import com.multi.vidulum.security.auth.AuthenticationResponse;
import com.multi.vidulum.security.auth.RegisterRequest;
import com.multi.vidulum.user.app.UserDto;
import com.multi.vidulum.user.app.UserRestController;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Integration test base class for vidulum-app.
 * Tests cross-module flows: user registration, portfolio onboarding, task management.
 */
@Slf4j
@SpringBootTest(classes = {FixedClockConfig.class, TestAiConfig.class})
@Import({AppIntegrationTest.TestCashFlowServiceClientConfig.class})
@ActiveProfiles("test")
public abstract class AppIntegrationTest {

    @TestConfiguration
    static class TestCashFlowServiceClientConfig {
        @Bean
        public CashFlowServiceClient cashFlowServiceClient(
                com.multi.vidulum.shared.cqrs.QueryGateway queryGateway,
                @Lazy com.multi.vidulum.shared.cqrs.CommandGateway commandGateway) {
            return new TestCashFlowServiceClient(queryGateway, commandGateway);
        }
    }

    protected static final MongoDBContainer mongoDBContainer;
    protected static final KafkaContainer kafka;

    static {
        mongoDBContainer = new MongoDBContainer("mongo:8.0.4");
        mongoDBContainer.start();

        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));
        kafka.start();

        log.info("AppIntegrationTest - Testcontainers started - MongoDB: {}, Kafka: {}",
                mongoDBContainer.getReplicaSetUrl(), kafka.getBootstrapServers());
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    protected QuoteRestController quoteRestController;

    @Autowired
    protected UserRestController userRestController;

    @Autowired
    protected AuthenticationController authenticationController;

    @Autowired
    protected PortfolioRestController portfolioRestController;

    @Autowired
    protected TaskRestController taskRestController;

    @BeforeEach
    public void beforeTest() {
        kafkaListenerEndpointRegistry.getListenerContainers().forEach(
                messageListenerContainer -> {
                    if (messageListenerContainer.isRunning()) {
                        try {
                            ContainerTestUtils.waitForAssignment(messageListenerContainer, 1);
                        } catch (IllegalStateException e) {
                            log.debug("Skipping partition wait for container: {}", e.getMessage());
                        }
                    }
                });
    }

    protected UserDto.UserSummaryJson createUser(String username, String password, String email) {
        String uniqueEmail = UUID.randomUUID().toString().substring(0, 8) + "_" + email;
        String uniqueUsername = username + "_" + UUID.randomUUID().toString().substring(0, 8);

        RegisterRequest registerRequest = RegisterRequest.builder()
                .username(uniqueUsername)
                .password(password)
                .email(uniqueEmail)
                .build();

        AuthenticationResponse response = authenticationController.register(registerRequest).getBody();

        return UserDto.UserSummaryJson.builder()
                .userId(response.getUserId())
                .username(uniqueUsername)
                .email(uniqueEmail)
                .isActive(true)
                .portfolioIds(java.util.Collections.emptyList())
                .build();
    }

    protected void activateUser(String userId) {
        userRestController.activateUser(userId);
    }

    protected UserDto.PortfolioRegistrationSummaryJson registerPortfolio(String name, String broker, String userId, String currency) {
        return userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name(name)
                        .broker(broker)
                        .userId(userId)
                        .allowedDepositCurrency(currency)
                        .build());
    }

    protected void depositMoney(PortfolioId portfolioId, Money money) {
        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(portfolioId.getId())
                        .money(money)
                        .build());
    }
}
