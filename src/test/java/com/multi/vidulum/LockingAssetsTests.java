package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.infrastructure.PnlMongoRepository;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.portfolio.domain.AssetNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.quotation.app.QuotationDto;
import com.multi.vidulum.quotation.app.QuoteRestController;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import com.multi.vidulum.trading.app.OrderRestController;
import com.multi.vidulum.trading.app.TradingAppConfig;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.app.TradeRestController;
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
import java.util.Map;
import java.util.Optional;

import static com.multi.vidulum.common.Side.BUY;
import static com.multi.vidulum.common.Side.SELL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
@Import({PortfolioAppConfig.class, TradingAppConfig.class})
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
    private TradeRestController tradeRestController;

    @Autowired
    private OrderRestController orderRestController;

    @Autowired
    private DomainPortfolioRepository portfolioRepository;

    @Autowired
    private TradeMongoRepository tradeMongoRepository;

    @Autowired
    private OrderMongoRepository orderMongoRepository;

    @Autowired
    private PnlMongoRepository pnlMongoRepository;

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
    void shouldLockCash() {
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

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("XYZ")
                        .broker("BINANCE")
                        .userId(persistedUser.getUserId())
                        .build());

        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .money(Money.of(100000.0, "USD"))
                        .build());

        // there is no any pending order
        assertThat(orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        TradingDto.OrderSummaryJson placedOrderSummary1 = orderRestController.placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        List<TradingDto.OrderSummaryJson> allOpenedOrders = orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        assertThat(allOpenedOrders).containsExactlyInAnyOrder(
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary1.getOrderId())
                        .originOrderId(placedOrderSummary1.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .status(OrderStatus.OPEN)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        // await until portfolio locks [27500 USD]
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            Portfolio portfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId())).get();
            Asset usdAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("USD").equals(asset.getTicker()))
                    .findFirst().orElseThrow(() -> new AssetNotFoundException(Ticker.of("USD")));
            return usdAsset.getLocked().equals(Quantity.of(27500.0)) && usdAsset.getFree().equals(Quantity.of(72500));
        });

        assertThat(portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId()))
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUserJson.getUserId())
                                .segmentedAssets(Map.of("Cash", List.of(
                                        PortfolioDto.AssetSummaryJson.builder()
                                                .ticker("USD")
                                                .fullName("American Dollar")
                                                .avgPurchasePrice(Price.one("USD"))
                                                .quantity(Quantity.of(100000.0))
                                                .locked(Quantity.of(27500.0))
                                                .free(Quantity.of(72500))
                                                .pctProfit(0)
                                                .profit(Money.of(0, "USD"))
                                                .currentPrice(Price.of(1, "USD"))
                                                .currentValue(Money.of(100000.0, "USD"))
                                                .tags(List.of())
                                                .build())))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());
    }

    @Test
    void shouldLockAndUnlockCash() {
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

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("XYZ")
                        .broker("BINANCE")
                        .userId(persistedUser.getUserId())
                        .build());

        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .money(Money.of(100000.0, "USD"))
                        .build());

        // there is no any pending order
        assertThat(orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        TradingDto.OrderSummaryJson placedOrderSummary1 = orderRestController.placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        List<TradingDto.OrderSummaryJson> allOpenedOrders = orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        assertThat(allOpenedOrders).containsExactlyInAnyOrder(
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary1.getOrderId())
                        .originOrderId(placedOrderSummary1.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .status(OrderStatus.OPEN)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        TradingDto.OrderSummaryJson cancelOrder = orderRestController.cancelOrder(placedOrderSummary1.getOrderId());
        assertThat(cancelOrder).isEqualTo(
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary1.getOrderId())
                        .originOrderId(placedOrderSummary1.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .status(OrderStatus.CANCELLED)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        assertThat(orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        // await until portfolio unlocks [27500 USD]
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            Portfolio portfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId())).get();
            Asset usdAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("USD").equals(asset.getTicker()))
                    .findFirst().orElseThrow(() -> new AssetNotFoundException(Ticker.of("USD")));
            return usdAsset.getLocked().isZero() && usdAsset.getFree().equals(Quantity.of(100000.0));
        });

        assertThat(portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId()))
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUserJson.getUserId())
                                .segmentedAssets(Map.of("Cash", List.of(
                                        PortfolioDto.AssetSummaryJson.builder()
                                                .ticker("USD")
                                                .fullName("American Dollar")
                                                .avgPurchasePrice(Price.one("USD"))
                                                .quantity(Quantity.of(100000.0))
                                                .locked(Quantity.zero())
                                                .free(Quantity.of(100000.0))
                                                .pctProfit(0)
                                                .profit(Money.of(0, "USD"))
                                                .currentPrice(Price.of(1, "USD"))
                                                .currentValue(Money.of(100000.0, "USD"))
                                                .tags(List.of())
                                                .build())))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());
    }

    @Test
    void shouldExecuteTradeAndLockCashTwoTimesAndUnlockCash() {
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

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("XYZ")
                        .broker("BINANCE")
                        .userId(persistedUser.getUserId())
                        .build());

        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .money(Money.of(100000.0, "USD"))
                        .build());

        tradeRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade1")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(1))
                .price(Price.of(60000.0, "USD"))
                .build());


        // await until portfolio will contain [1 BTC]
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            Portfolio portfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId())).get();
            Optional<Asset> usdAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("BTC").equals(asset.getTicker()))
                    .findFirst();
            return usdAsset.map(asset -> asset.getQuantity().equals(Quantity.of(1))).orElse(false);
        });

        TradingDto.OrderSummaryJson placedOrderSummary1 = orderRestController.placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(50000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        TradingDto.OrderSummaryJson placedOrderSummary2 = orderRestController.placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .targetPrice(Price.of(70000, "USD"))
                        .stopPrice(Price.of(56000, "USD"))
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        List<TradingDto.OrderSummaryJson> allOpenedOrders = orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        assertThat(allOpenedOrders).containsExactlyInAnyOrder(
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary1.getOrderId())
                        .originOrderId(placedOrderSummary1.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .status(OrderStatus.OPEN)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(50000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build(),
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary2.getOrderId())
                        .originOrderId(placedOrderSummary2.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .status(OrderStatus.OPEN)
                        .targetPrice(Price.of(70000, "USD"))
                        .stopPrice(Price.of(56000, "USD"))
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );


        // await until portfolio locks [27500 USD] and [0.5 BTC]
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            Portfolio portfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId())).get();
            Asset usdAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("USD").equals(asset.getTicker()))
                    .findFirst().orElseThrow(() -> new AssetNotFoundException(Ticker.of("USD")));

            Asset btcAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("BTC").equals(asset.getTicker()))
                    .findFirst().orElseThrow(() -> new AssetNotFoundException(Ticker.of("BTC")));

            return usdAsset.getLocked().equals(Quantity.of(25000)) && btcAsset.getLocked().equals(Quantity.of(0.5));
        });

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId());
        assertThat(aggregatedPortfolio)
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUserJson.getUserId())
                                .segmentedAssets(Map.of(
                                        "Cash", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("USD")
                                                        .fullName("American Dollar")
                                                        .avgPurchasePrice(Price.one("USD"))
                                                        .quantity(Quantity.of(40000.0))
                                                        .locked(Quantity.of(25000.0000))
                                                        .free(Quantity.of(15000))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(1, "USD"))
                                                        .currentValue(Money.of(40000.0, "USD"))
                                                        .tags(List.of())
                                                        .build()),
                                        "Crypto", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("BTC")
                                                        .fullName("Bitcoin")
                                                        .avgPurchasePrice(Price.of(60000.0, "USD"))
                                                        .quantity(Quantity.of(1))
                                                        .locked(Quantity.of(0.5))
                                                        .free(Quantity.of(0.5))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(60000.0000, "USD"))
                                                        .currentValue(Money.of(60000.0000, "USD"))
                                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                                        .build())
                                ))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());
    }

    @Test
    void shouldExecuteTwoPurchaseTradesAndOneSaleTrade() {
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

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("XYZ")
                        .broker("BINANCE")
                        .userId(persistedUser.getUserId())
                        .build());

        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .money(Money.of(100000.0, "USD"))
                        .build());

        tradeRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade1")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.4))
                .price(Price.of(60000.0, "USD"))
                .build());


        tradeRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade2")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.6))
                .price(Price.of(60000.0, "USD"))
                .build());

        // await until portfolio will contain [1 BTC]
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            Portfolio portfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId())).get();
            Optional<Asset> usdAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("BTC").equals(asset.getTicker()))
                    .findFirst();
            return usdAsset.map(asset -> asset.getQuantity().equals(Quantity.of(1))).orElse(false);
        });

        assertThat(portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId()))
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUserJson.getUserId())
                                .segmentedAssets(Map.of(
                                        "Cash", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("USD")
                                                        .fullName("American Dollar")
                                                        .avgPurchasePrice(Price.one("USD"))
                                                        .quantity(Quantity.of(40000.0))
                                                        .locked(Quantity.of(0))
                                                        .free(Quantity.of(40000.0))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(1, "USD"))
                                                        .currentValue(Money.of(40000.0, "USD"))
                                                        .tags(List.of())
                                                        .build()),
                                        "Crypto", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("BTC")
                                                        .fullName("Bitcoin")
                                                        .avgPurchasePrice(Price.of(60000.0, "USD"))
                                                        .quantity(Quantity.of(1))
                                                        .locked(Quantity.zero())
                                                        .free(Quantity.of(1))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(60000.0000, "USD"))
                                                        .currentValue(Money.of(60000.0000, "USD"))
                                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                                        .build())
                                ))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());

        tradeRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade3")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(0.3))
                .price(Price.of(60000.0, "USD"))
                .build());

        tradeRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(0.1))
                .price(Price.of(60000.0, "USD"))
                .build());

        // await until portfolio will contain [0.5 BTC]
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            Portfolio portfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId())).get();
            Optional<Asset> btcAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("BTC").equals(asset.getTicker()))
                    .findFirst();
            return btcAsset.map(asset -> asset.getQuantity().equals(Quantity.of(0.6))).orElse(false);
        });

        assertThat(portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId()))
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUserJson.getUserId())
                                .segmentedAssets(Map.of(
                                        "Cash", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("USD")
                                                        .fullName("American Dollar")
                                                        .avgPurchasePrice(Price.one("USD"))
                                                        .quantity(Quantity.of(40000.0 + 18000.0 + 6000.0))
                                                        .locked(Quantity.of(0))
                                                        .free(Quantity.of(40000.0 + 18000.0 + 6000.0))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(1, "USD"))
                                                        .currentValue(Money.of(40000.0 + 18000.0 + 6000.0, "USD"))
                                                        .tags(List.of())
                                                        .build()),
                                        "Crypto", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("BTC")
                                                        .fullName("Bitcoin")
                                                        .avgPurchasePrice(Price.of(60000.0, "USD"))
                                                        .quantity(Quantity.of(0.6))
                                                        .locked(Quantity.zero())
                                                        .free(Quantity.of(0.6))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(60000.0000, "USD"))
                                                        .currentValue(Money.of(36000.0000, "USD"))
                                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                                        .build())
                                ))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());
    }
}
