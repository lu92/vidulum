package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioRestClient;
import com.multi.vidulum.quotation.app.QuoteRestController;
import com.multi.vidulum.risk_management.app.RiskManagementRestController;
import com.multi.vidulum.pnl.app.PnlRestController;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.infrastructure.PnlMongoRepository;
import com.multi.vidulum.trading.app.OrderRestController;
import com.multi.vidulum.trading.app.TradeRestController;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.OrderFactory;
import com.multi.vidulum.trading.infrastructure.OrderMongoRepository;
import com.multi.vidulum.trading.infrastructure.TradeMongoRepository;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.multi.vidulum.common.Side.BUY;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Self-contained integration test base class for wealth module.
 * Independent from vidulum-app — uses test stubs for user domain.
 */
@Slf4j
@SpringBootTest(classes = {FixedClockConfig.class})
@ActiveProfiles("test")
public abstract class WealthIntegrationTest {

    protected static final MongoDBContainer mongoDBContainer;
    protected static final KafkaContainer kafka;

    static {
        mongoDBContainer = new MongoDBContainer("mongo:8.0.4");
        mongoDBContainer.start();

        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));
        kafka.start();

        log.info("WealthIntegrationTest - Testcontainers started - MongoDB: {}, Kafka: {}",
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
    protected PortfolioRestController portfolioRestController;

    @Autowired
    protected TradeRestController tradeRestController;

    @Autowired
    protected OrderRestController orderRestController;

    @Autowired
    protected DomainPortfolioRepository portfolioRepository;

    @Autowired
    protected TradeMongoRepository tradeMongoRepository;

    @Autowired
    protected OrderMongoRepository orderMongoRepository;

    @Autowired
    protected DomainOrderRepository orderRepository;

    @Autowired
    protected DomainPnlRepository pnlRepository;

    @Autowired
    protected RiskManagementRestController riskManagementRestController;

    @Autowired
    protected PnlRestController pnlRestController;

    @Autowired
    protected PnlMongoRepository pnlMongoRepository;

    @Autowired
    protected PortfolioFactory portfolioFactory;

    @Autowired
    protected OrderFactory orderFactory;

    @Autowired
    protected PortfolioRestClient portfolioRestClient;

    @Autowired
    protected InMemoryAuthenticatableUserRepository testUserRepository;

    protected JsonFormatter jsonFormatter = new JsonFormatter();

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

    // ========== User helpers (no user domain dependency) ==========

    /**
     * Creates a test user in-memory (no user domain needed).
     * Returns a simple record with userId for use in wealth tests.
     */
    protected TestUserSummary createUser(String username, String password, String email) {
        String uniqueEmail = UUID.randomUUID().toString().substring(0, 8) + "_" + email;
        String uniqueUsername = username + "_" + UUID.randomUUID().toString().substring(0, 8);

        InMemoryAuthenticatableUserRepository.TestUser user =
                testUserRepository.saveUser(uniqueUsername, password, uniqueEmail);

        return new TestUserSummary(user.userId(), uniqueUsername, uniqueEmail);
    }

    protected void activateUser(String userId) {
        // No-op: test users are always active
    }

    /**
     * Creates a portfolio directly via PortfolioRestClient (bypasses user domain).
     */
    protected TestPortfolioSummary registerPortfolio(String name, String broker, String userId, String currency) {
        PortfolioId portfolioId = portfolioRestClient.createPortfolio(
                name, new UserId(userId), Broker.of(broker), Currency.of(currency));
        return new TestPortfolioSummary(portfolioId.getId(), broker);
    }

    protected void depositMoney(PortfolioId portfolioId, Money money) {
        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(portfolioId.getId())
                        .money(money)
                        .build());
    }

    protected TradingDto.OrderSummaryJson placeOrder(TradingDto.PlaceOrderJson placeOrderJson) {
        return orderRestController.placeOrder(placeOrderJson);
    }

    protected void makeTrade(TradingDto.TradeExecutedJson tradeExecutedJson) {
        tradeRestController.makeTrade(tradeExecutedJson);
    }

    protected String uniqueOriginOrderId(String suffix) {
        return "order-" + UUID.randomUUID().toString().substring(0, 8) + "-" + suffix;
    }

    protected String uniqueOriginTradeId(String suffix) {
        return "trade-" + UUID.randomUUID().toString().substring(0, 8) + "-" + suffix;
    }

    protected static final TradingDto.Fee ZERO_FEE = TradingDto.Fee.builder()
            .exchangeCurrencyFee(Money.zero("USD"))
            .transactionFee(Money.zero("USD"))
            .build();

    protected void awaitUntilAssetMetadataIsEqualTo(
            PortfolioId portfolioId,
            Ticker assetTicker,
            Quantity expectedQuantity,
            Quantity expectedLocked,
            Quantity expectedFree) {
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            PortfolioDto.PortfolioSummaryJson portfolioSummaryJson = portfolioRestController.getPortfolio(portfolioId.getId(), "USD");
            log.info(jsonFormatter.formatToPrettyJson(portfolioSummaryJson));
            return portfolioSummaryJson.getAssets().stream()
                    .filter(asset -> assetTicker.equals(Ticker.of(asset.getTicker())))
                    .findFirst()
                    .map(asset ->
                            asset.getQuantity().equals(expectedQuantity) &&
                                    asset.getLocked().equals(expectedLocked) &&
                                    asset.getFree().equals(expectedFree))
                    .orElse(false);
        });
    }

    // ========== Simple DTOs for test helpers ==========

    public record TestUserSummary(String userId, String username, String email) {}
    public record TestPortfolioSummary(String portfolioId, String broker) {}
}
