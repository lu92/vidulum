package com.multi.vidulum.trading.domain;


import com.multi.vidulum.JsonFormatter;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.pnl.app.PnlRestController;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.infrastructure.PnlMongoRepository;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.quotation.app.QuoteRestController;
import com.multi.vidulum.risk_management.app.RiskManagementRestController;
import com.multi.vidulum.shared.TradeAppliedToPortfolioEventListener;
import com.multi.vidulum.trading.app.OrderRestController;
import com.multi.vidulum.trading.app.TradeRestController;
import com.multi.vidulum.trading.app.TradingAppConfig;
import com.multi.vidulum.trading.infrastructure.OrderMongoRepository;
import com.multi.vidulum.trading.infrastructure.TradeMongoRepository;
import com.multi.vidulum.user.app.UserRestController;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
@SpringBootTest
@Import({PortfolioAppConfig.class, TradingAppConfig.class})
@Testcontainers
@DirtiesContext
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
public abstract class IntegrationTest {
    @Container
    protected static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.6");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
    }

    @Autowired
    protected QuoteRestController quoteRestController;

    @Autowired
    protected UserRestController userRestController;

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
    protected TradeAppliedToPortfolioEventListener tradeAppliedToPortfolioEventListener;

    @Autowired
    protected PnlMongoRepository pnlMongoRepository;

    protected JsonFormatter jsonFormatter = new JsonFormatter();

    @Before
    protected void cleanUp() {
        log.info("Lets clean the data");
        tradeMongoRepository.deleteAll();
        orderMongoRepository.deleteAll();
        pnlMongoRepository.deleteAll();
        quoteRestController.clearCaches();
    }

    protected void awaitUntilAssetMetadataIsEqualTo(
            PortfolioId portfolioId,
            Ticker assetTicker,
            Quantity expectedQuantity,
            Quantity expectedLocked,
            Quantity expectedFree) {
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            PortfolioDto.PortfolioSummaryJson portfolioSummaryJson = portfolioRestController.getPortfolio(portfolioId.getId());
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
}
