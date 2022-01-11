package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.app.PnlRestController;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.infrastructure.PnlMongoRepository;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioDto.PortfolioSummaryJson;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.quotation.app.QuotationDto;
import com.multi.vidulum.quotation.app.QuoteRestController;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import com.multi.vidulum.risk_management.app.RiskManagementRestController;
import com.multi.vidulum.shared.TradeAppliedToPortfolioEventListener;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.app.TradingRestController;
import com.multi.vidulum.trading.domain.DomainTradeRepository;
import com.multi.vidulum.trading.infrastructure.OrderMongoRepository;
import com.multi.vidulum.trading.infrastructure.TradeMongoRepository;
import com.multi.vidulum.user.app.UserDto;
import com.multi.vidulum.user.app.UserRestController;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.jupiter.api.Test;
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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.multi.vidulum.common.Side.BUY;
import static com.multi.vidulum.common.Side.SELL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
@Import(PortfolioAppConfig.class)
@Testcontainers
@DirtiesContext
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
class LockingAssetsTests {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.6");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
    }

    @Autowired
    private QuoteRestController quoteRestController;

    @Autowired
    private UserRestController userRestController;

    @Autowired
    private PortfolioRestController portfolioRestController;

    @Autowired
    private TradingRestController tradingRestController;

    @Autowired
    private RiskManagementRestController riskManagementRestController;

    @Autowired
    private PnlRestController pnlRestController;

    @Autowired
    private DomainPortfolioRepository portfolioRepository;

    @Autowired
    private DomainTradeRepository tradeRepository;

    @Autowired
    private TradeMongoRepository tradeMongoRepository;

    @Autowired
    private OrderMongoRepository orderMongoRepository;

    @Autowired
    private PnlMongoRepository pnlMongoRepository;

    @Autowired
    private DomainPnlRepository pnlRepository;

    @Autowired
    private TradeAppliedToPortfolioEventListener tradeAppliedToPortfolioEventListener;

    private JsonFormatter jsonFormatter = new JsonFormatter();

    @Before
    void cleanUp() {
        log.info("Lets clean the data");
        tradeMongoRepository.deleteAll();
        orderMongoRepository.deleteAll();
        pnlMongoRepository.deleteAll();
        quoteRestController.clearCaches();
    }

    @Test
    void shouldBuyBitcoinTest() {
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
                .build());
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("BTC")
                .fullName("Bitcoin")
                .segment("Crypto")
                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                .build());

        Awaitility.await().atMost(30, SECONDS).until(() -> {
            try {
                AssetPriceMetadata priceMetadata = quoteRestController.fetch("BINANCE", "USD", "USD");
                log.info("[{}] Loaded price - [{}]", priceMetadata.getSymbol().getId(), priceMetadata.getCurrentPrice());
                return priceMetadata.getSymbol().getId().equals("USD/USD");
            } catch (QuoteNotFoundException e) {
                return false;
            }
        });

        UserDto.UserSummaryJson createdUserJson = userRestController.createUser(
                UserDto.CreateUserJson.builder()
                        .username("lu92")
                        .password("secret")
                        .email("lu92@email.com")
                        .build());

        Awaitility.await().atMost(5, SECONDS).until(() -> {
            UserId existingUserId = UserId.of(createdUserJson.getUserId());
            return pnlRepository.findByUser(existingUserId).isPresent();
        });

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("XYZ")
                        .broker("BINANCE")
                        .userId(persistedUser.getUserId())
                        .build());

        AtomicLong appliedTradesOnPortfolioNumber = new AtomicLong();
        tradeAppliedToPortfolioEventListener.clearCallbacks();
        tradeAppliedToPortfolioEventListener.registerCallback(event -> {
            log.info("Following trade [{}] applied to portfolio", event);
            appliedTradesOnPortfolioNumber.incrementAndGet();
        });

        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .money(Money.of(100000.0, "USD"))
                        .build());

        // there is no any pending order
        assertThat(tradingRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        TradingDto.OrderSummaryJson placedOrderSummary1 = tradingRestController.placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Money.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        List<TradingDto.OrderSummaryJson> allOpenedOrders = tradingRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        assertThat(allOpenedOrders).containsExactlyInAnyOrder(
          TradingDto.OrderSummaryJson.builder()
                  .orderId(placedOrderSummary1.getOrderId())
                  .originOrderId(placedOrderSummary1.getOriginOrderId())
                  .portfolioId(registeredPortfolio.getPortfolioId())
                  .symbol("BTC/USD")
                  .type(OrderType.LIMIT)
                  .side(BUY)
                  .status(Status.OPEN)
                  .targetPrice(null)
                  .stopPrice(null)
                  .limitPrice(Money.of(55000, "USD"))
                  .quantity(Quantity.of(0.5))
                  .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                  .build()
        );

        PortfolioSummaryJson portfolio = portfolioRestController.getPortfolio(registeredPortfolio.getPortfolioId());
        System.out.println(portfolio);



    }
}
