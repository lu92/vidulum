package com.multi.vidulum.trading.domain;


import com.multi.vidulum.JsonFormatter;
import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.app.PnlRestController;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.infrastructure.PnlMongoRepository;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.quotation.app.QuoteRestController;
import com.multi.vidulum.risk_management.app.RiskManagementRestController;
import com.multi.vidulum.trading.app.OrderRestController;
import com.multi.vidulum.trading.app.TradeRestController;
import com.multi.vidulum.trading.app.TradingAppConfig;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.infrastructure.OrderMongoRepository;
import com.multi.vidulum.trading.infrastructure.TradeMongoRepository;
import com.multi.vidulum.user.app.UserDto;
import com.multi.vidulum.user.app.UserRestController;
import lombok.Builder;
import lombok.Value;
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

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.multi.vidulum.common.Side.BUY;
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
    protected PnlMongoRepository pnlMongoRepository;

    @Autowired
    protected PortfolioFactory portfolioFactory;

    @Autowired
    protected OrderFactory orderFactory;

    protected JsonFormatter jsonFormatter = new JsonFormatter();

    @Before
    public void cleanUp() {
        log.info("Lets clean the data");
        tradeMongoRepository.deleteAll();
        orderMongoRepository.deleteAll();
        pnlMongoRepository.deleteAll();
        quoteRestController.clearCaches();
    }

    protected static final TradingDto.Fee ZERO_FEE = TradingDto.Fee.builder()
            .exchangeCurrencyFee(Money.zero("USD"))
            .transactionFee(Money.zero("USD"))
            .build();

    protected UserDto.UserSummaryJson createUser(String username, String password, String email) {
        return userRestController.createUser(
                UserDto.CreateUserJson.builder()
                        .username(username)
                        .password(password)
                        .email(email)
                        .build());
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

    protected TradingDto.OrderSummaryJson placeOrder(TradingDto.PlaceOrderJson placeOrderJson) {
        return orderRestController.placeOrder(placeOrderJson);
    }

    protected void makeTrade(TradingDto.TradeExecutedJson tradeExecutedJson) {
        tradeRestController.makeTrade(tradeExecutedJson);
    }

    protected void makeDirectTrade(PortfolioId portfolioId, String originCurrency, DirectTrade directTrade) {

        // get portfolio's data
        PortfolioDto.PortfolioSummaryJson portfolio = portfolioRestController.getPortfolio(portfolioId.getId(), originCurrency);

        // prepare request for order
        TradingDto.PlaceOrderJson placeOrderJson = TradingDto.PlaceOrderJson.builder()
                .originOrderId(UUID.randomUUID().toString())
                .portfolioId(portfolio.getPortfolioId())
                .broker(portfolio.getBroker())
                .symbol(directTrade.getSymbol())
                .type(OrderType.LIMIT)
                .side(directTrade.getSide())
                .targetPrice(null)
                .stopPrice(null)
                .limitPrice(directTrade.getPrice())
                .quantity(directTrade.getQuantity())
                .originDateTime(directTrade.getOriginDateTime())
                .build();

        // execute order
        TradingDto.OrderSummaryJson orderSummaryJson = orderRestController.placeOrder(placeOrderJson);


        Ticker assetTicker = directTrade.getSide() == BUY ? Symbol.of(directTrade.getSymbol()).getOrigin() : Symbol.of(directTrade.getSymbol()).getDestination();

        Quantity quantityOfAsset = portfolio.getAssets().stream()
                .filter(assetSummaryJson -> Ticker.of(assetSummaryJson.getTicker()).equals(assetTicker))
                .findFirst()
                .map(PortfolioDto.AssetSummaryJson::getQuantity)
                .orElse(Quantity.zero());

        Quantity assetQuantityOfTrade =
                directTrade.getSide() == BUY ?
                        directTrade.getQuantity() :
                        Quantity.of(directTrade.getPrice().multiply(directTrade.getQuantity()).getAmount().doubleValue());

        Quantity expectedQuantity = quantityOfAsset.plus(assetQuantityOfTrade);

        makeTrade(
                TradingDto.TradeExecutedJson.builder()
                        .originTradeId(UUID.randomUUID().toString())
                        .orderId(orderSummaryJson.getOrderId())
                        .portfolioId(portfolioId.getId())
                        .userId(portfolio.getUserId())
                        .symbol(directTrade.getSymbol())
                        .subName("")
                        .side(directTrade.getSide())
                        .quantity(directTrade.getQuantity())
                        .price(directTrade.getPrice())
                        .fee(TradingDto.Fee.builder()
                                .exchangeCurrencyFee(directTrade.getExchangeCurrencyFee())
                                .transactionFee(directTrade.getTransactionFee())
                                .build())
                        .exchangeCurrencyRate(directTrade.getExchangeCurrencyRate())
                        .originDateTime(directTrade.getOriginDateTime())
                        .build());

        Awaitility.await().atMost(10, SECONDS).until(() -> {

            PortfolioDto.PortfolioSummaryJson portfolioSummaryJson = portfolioRestController.getPortfolio(portfolioId.getId(), originCurrency);
            log.info(jsonFormatter.formatToPrettyJson(portfolioSummaryJson));

            Optional<PortfolioDto.AssetSummaryJson> assetSummary = portfolioSummaryJson.getAssets().stream()
                    .filter(asset -> assetTicker.equals(Ticker.of(asset.getTicker())))
                    .findFirst();

            return assetSummary
                    .map(PortfolioDto.AssetSummaryJson::getQuantity)
                    .map(quantity -> quantity.equals(expectedQuantity))
                    .orElse(false);
        });
    }

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

    @Value
    @Builder
    public static class DirectTrade {
        String originOrderId;
        String portfolioId;
        String broker;
        String symbol;
        Side side;
        Quantity quantity;
        Price price;
        Money exchangeCurrencyFee;
        ExchangeRate exchangeCurrencyRate;
        Money transactionFee;
        ZonedDateTime originDateTime;
    }
}
