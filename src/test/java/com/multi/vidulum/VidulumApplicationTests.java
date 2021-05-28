package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.quotation.app.QuotationDto;
import com.multi.vidulum.quotation.app.QuoteRestController;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import com.multi.vidulum.shared.TradeAppliedToPortfolioEventListener;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.app.TradingRestController;
import com.multi.vidulum.trading.domain.DomainTradeRepository;
import com.multi.vidulum.trading.infrastructure.TradeMongoRepository;
import com.multi.vidulum.user.app.UserDto;
import com.multi.vidulum.user.app.UserRestController;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
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
import java.util.concurrent.atomic.AtomicLong;

import static com.multi.vidulum.common.Side.BUY;
import static com.multi.vidulum.common.Side.SELL;
import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
@SpringBootTest
@Import(PortfolioAppConfig.class)
@Testcontainers
@DirtiesContext
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
class VidulumApplicationTests {

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
    private DomainPortfolioRepository portfolioRepository;

    @Autowired
    private DomainTradeRepository tradeRepository;

    @Autowired
    private TradeMongoRepository tradeMongoRepository;

    @Autowired
    private TradeAppliedToPortfolioEventListener tradeAppliedToPortfolioEventListener;

    @Before
    void cleanUp() {
        log.info("Lets clean the data");
        tradeMongoRepository.deleteAll();
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

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();

        Assertions.assertThat(persistedUser).isEqualTo(expectedUserSummary);

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

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade1")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName("")
                .side(BUY)
                .quantity(Quantity.of(1))
                .price(Money.of(60000.0, "USD"))
                .build());

        Awaitility.await().atMost(10, SECONDS).until(() -> appliedTradesOnPortfolioNumber.longValue() == 1);

        Optional<Portfolio> optionalPortfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId()));
        Assertions.assertThat(optionalPortfolio.isPresent()).isTrue();
        Portfolio portfolio = optionalPortfolio.get();
        System.out.println(portfolio);


        Portfolio expectedPortfolio = Portfolio.builder()
                .portfolioId(PortfolioId.of(registeredPortfolio.getPortfolioId()))
                .userId(UserId.of(persistedUser.getUserId()))
                .name("XYZ")
                .broker(Broker.of("BINANCE"))
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .fullName("American Dollar")
                                .segment(Segment.of("Cash"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.one("USD"))
                                .quantity(Quantity.of(40000))
                                .tags(List.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .fullName("Not found")
                                .segment(Segment.unknown())
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.of(60000, "USD"))
                                .quantity(Quantity.of(1))
                                .tags(List.of())
                                .build()
                ))
                .investedBalance(Money.of(100000.0, "USD"))
                .build();

        Assertions.assertThat(portfolio).isEqualTo(expectedPortfolio);
        List<TradingDto.TradeSummaryJson> allTrades = tradingRestController.getAllTrades(createdUserJson.getUserId(), registeredPortfolio.getPortfolioId());
        Assertions.assertThat(allTrades).hasSize(1);
    }

    @Test
    void buyAndSellImmediatelyBitcoinTest() {
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
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

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();
        Assertions.assertThat(persistedUser).isEqualTo(expectedUserSummary);


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

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade1")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(1))
                .price(Money.of(60000.0, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade2")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(1))
                .price(Money.of(80000, "USD"))
                .build());


        Awaitility.await().atMost(10, SECONDS).until(() -> appliedTradesOnPortfolioNumber.longValue() == 2);

        Optional<Portfolio> optionalPortfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId()));
        Assertions.assertThat(optionalPortfolio.isPresent()).isTrue();
        Portfolio portfolio = optionalPortfolio.get();
        System.out.println(portfolio);

        Portfolio expectedPortfolio = Portfolio.builder()
                .portfolioId(PortfolioId.of(registeredPortfolio.getPortfolioId()))
                .userId(UserId.of(persistedUser.getUserId()))
                .name("XYZ")
                .broker(Broker.of("BINANCE"))
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .fullName("American Dollar")
                                .segment(Segment.of("Cash"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.one("USD"))
                                .quantity(Quantity.of(120000))
                                .tags(List.of())
                                .build()
                ))
                .investedBalance(Money.of(100000.0, "USD"))
                .build();

        Assertions.assertThat(portfolio).isEqualTo(expectedPortfolio);
        List<TradingDto.TradeSummaryJson> allTrades = tradingRestController.getAllTrades(createdUserJson.getUserId(), registeredPortfolio.getPortfolioId());
        Assertions.assertThat(allTrades).hasSize(2);
    }

    @Test
    void shouldExecuteTrades() {
        quoteRestController.changePrice("PM", "XAU", "USD", 1800, "USD", 0);
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "ETH", "USD", 2850, "USD", 1.09);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
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

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();
        Assertions.assertThat(persistedUser).isEqualTo(expectedUserSummary);

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


        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.1))
                .price(Money.of(60000.0, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.1))
                .price(Money.of(30000, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.1))
                .price(Money.of(30000, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(0.1))
                .price(Money.of(40000, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.75))
                .price(Money.of(2800, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.25))
                .price(Money.of(2800, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.5))
                .price(Money.of(3400, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(0.2))
                .price(Money.of(3000, "USD"))
                .build());

        Awaitility.await().atMost(10, SECONDS).until(() -> appliedTradesOnPortfolioNumber.longValue() == 8);

        Optional<Portfolio> optionalPortfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId()));
        Assertions.assertThat(optionalPortfolio.isPresent()).isTrue();
        Portfolio portfolio = optionalPortfolio.get();
        System.out.println(portfolio);


        Portfolio expectedPortfolio = Portfolio.builder()
                .portfolioId(PortfolioId.of(registeredPortfolio.getPortfolioId()))
                .userId(UserId.of(persistedUser.getUserId()))
                .name("XYZ")
                .broker(Broker.of("BINANCE"))
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .fullName("American Dollar")
                                .segment(Segment.of("Cash"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.one("USD"))
                                .quantity(Quantity.of(88100.0))
                                .tags(List.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .fullName("Not found")
                                .segment(Segment.unknown())
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.of(40000, "USD"))
                                .quantity(Quantity.of(0.20000000000000004))
                                .tags(List.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("ETH"))
                                .fullName("Not found")
                                .segment(Segment.unknown())
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.of(3000, "USD"))
                                .quantity(Quantity.of(1.3))
                                .tags(List.of())
                                .build()
                ))
                .investedBalance(Money.of(100000.0, "USD"))
                .build();

        Assertions.assertThat(portfolio).isEqualTo(expectedPortfolio);
        List<TradingDto.TradeSummaryJson> allTrades = tradingRestController.getAllTrades(createdUserJson.getUserId(), registeredPortfolio.getPortfolioId());
        Assertions.assertThat(allTrades).hasSize(8);
    }

    @Test
    void shouldPersistPortfolioForPreciousMetals() {
        quoteRestController.changePrice("PM", "XAU", "USD", 1800, "USD", 0);
        quoteRestController.changePrice("PM", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("PM", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("XAU")
                .fullName("Gold")
                .segment("Precious Metals")
                .tags(List.of("Gold", "Precious Metals"))
                .build());
        quoteRestController.registerAssetBasicInfo("PM", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
                .build());

        Awaitility.await().atMost(30, SECONDS).until(() -> {
            try {
                AssetPriceMetadata priceMetadata = quoteRestController.fetch("PM", "USD", "USD");
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

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();

        Assertions.assertThat(persistedUser).isEqualTo(expectedUserSummary);

        UserDto.PortfolioRegistrationSummaryJson registeredPreciousMetalsPortfolio = userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("Precious Metals 1")
                        .broker("PM")
                        .userId(persistedUser.getUserId())
                        .build());

        UserDto.PortfolioRegistrationSummaryJson registeredPreciousMetalsPortfolio2 = userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("Precious Metals 2")
                        .broker("PM")
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
                        .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                        .money(Money.of(2*1800 + 1820, "USD"))
                        .build());

        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(registeredPreciousMetalsPortfolio2.getPortfolioId())
                        .money(Money.of(1810, "USD"))
                        .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade1")
                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAU/USD")
                .subName("Maple Leaf")
                .side(BUY)
                .quantity(Quantity.of(2, "oz"))
                .price(Money.of(1800, "USD"))
                .originDateTime(ZonedDateTime.parse("2021-02-01T06:24:11Z"))
                .build());


        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade2")
                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAU/USD")
                .subName("Krugerrand")
                .side(BUY)
                .quantity(Quantity.of(1, "oz"))
                .price(Money.of(1820, "USD"))
                .originDateTime(ZonedDateTime.parse("2021-03-02T12:14:11Z"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade3")
                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAU/USD")
                .subName("Maple Leaf")
                .side(SELL)
                .quantity(Quantity.of(1, "oz"))
                .price(Money.of(1850, "USD"))
                .originDateTime(ZonedDateTime.parse("2021-04-01T16:24:11Z"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade4")
                .portfolioId(registeredPreciousMetalsPortfolio2.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAU/USD")
                .subName("Maple Leaf")
                .side(BUY)
                .quantity(Quantity.of(1, "oz"))
                .price(Money.of(1800, "USD"))
                .originDateTime(ZonedDateTime.parse("2021-02-01T06:24:11Z"))
                .build());

        Portfolio expectedPortfolio = Portfolio.builder()
                .portfolioId(PortfolioId.of(registeredPreciousMetalsPortfolio.getPortfolioId()))
                .userId(UserId.of(persistedUser.getUserId()))
                .name("Precious Metals 1")
                .broker(Broker.of("PM"))
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("XAU"))
                                .fullName("Gold")
                                .segment(Segment.of("Precious Metals"))
                                .subName(SubName.of("Maple Leaf"))
                                .avgPurchasePrice(Money.of(1750, "USD"))
                                .quantity(Quantity.of(1, "oz"))
                                .tags(List.of("Gold", "Precious Metals"))
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("XAU"))
                                .fullName("Gold")
                                .segment(Segment.of("Precious Metals"))
                                .subName(SubName.of("Krugerrand"))
                                .avgPurchasePrice(Money.of(1820, "USD"))
                                .quantity(Quantity.of(1, "oz"))
                                .tags(List.of("Gold", "Precious Metals"))
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .fullName("American Dollar")
                                .segment(Segment.of("Cash"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.one("USD"))
                                .quantity(Quantity.of(1850, "Number"))
                                .tags(List.of())
                                .build()
                ))
                .investedBalance(Money.of(2*1800 + 1820, "USD"))
                .build();

        Portfolio expectedPortfolio2 = Portfolio.builder()
                .portfolioId(PortfolioId.of(registeredPreciousMetalsPortfolio2.getPortfolioId()))
                .userId(UserId.of(persistedUser.getUserId()))
                .name("Precious Metals 2")
                .broker(Broker.of("PM"))
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .fullName("American Dollar")
                                .segment(Segment.of("Cash"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.one("USD"))
                                .quantity(Quantity.of(10, "Number"))
                                .tags(List.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("XAU"))
                                .fullName("Gold")
                                .segment(Segment.of("Precious Metals"))
                                .subName(SubName.of("Maple Leaf"))
                                .avgPurchasePrice(Money.of(1800, "USD"))
                                .quantity(Quantity.of(1, "oz"))
                                .tags(List.of("Gold", "Precious Metals"))
                                .build()
                ))
                .investedBalance(Money.of(1810, "USD"))
                .build();

        Awaitility.await().atMost(100, SECONDS).until(() -> appliedTradesOnPortfolioNumber.longValue() == 4);

        Optional<Portfolio> optionalPortfolio = portfolioRepository.findById(PortfolioId.of(registeredPreciousMetalsPortfolio.getPortfolioId()));
        Assertions.assertThat(optionalPortfolio.get()).isEqualTo(expectedPortfolio);

        List<TradingDto.TradeSummaryJson> allTrades = tradingRestController.getAllTrades(createdUserJson.getUserId(), registeredPreciousMetalsPortfolio.getPortfolioId());
        Assertions.assertThat(allTrades).hasSize(3);

        List<TradingDto.TradeSummaryJson> lastTwoTrades = tradingRestController.getTradesInDateRange(
                createdUserJson.getUserId(),
                registeredPreciousMetalsPortfolio.getPortfolioId(),
                ZonedDateTime.parse("2021-03-01T00:00:00Z"),
                ZonedDateTime.parse("2021-05-01T00:00:00Z"));
        System.out.println(lastTwoTrades);
        Assertions.assertThat(lastTwoTrades).hasSize(2);


        Optional<Portfolio> optionalPortfolio2 = portfolioRepository.findById(PortfolioId.of(registeredPreciousMetalsPortfolio2.getPortfolioId()));
        Assertions.assertThat(optionalPortfolio2.get()).isEqualTo(expectedPortfolio2);


        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolioJson = portfolioRestController.getAggregatedPortfolio(registeredPreciousMetalsPortfolio.getUserId());

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregatedPortfolio = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(registeredPreciousMetalsPortfolio.getUserId())
                .segmentedAssets(Map.of(
                        "Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Money.one("USD"))
                                        .quantity(Quantity.of(1850))
                                        .tags(List.of())
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Money.of(1, "USD"))
                                        .currentValue(Money.of(1850, "USD"))
                                        .build()),
                        "Precious Metals", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("XAU")
                                        .fullName("Gold")
                                        .avgPurchasePrice(Money.of(1750,"USD"))
                                        .quantity(Quantity.of(1, "oz"))
                                        .tags(List.of("Gold", "Precious Metals"))
                                        .pctProfit(0.02857142)
                                        .profit(Money.of(50, "USD"))
                                        .currentPrice(Money.of(1800, "USD"))
                                        .currentValue(Money.of(1800, "USD"))
                                        .build(),
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("XAU")
                                        .fullName("Gold")
                                        .avgPurchasePrice(Money.of(1820,"USD"))
                                        .quantity(Quantity.of(1, "oz"))
                                        .tags(List.of("Gold", "Precious Metals"))
                                        .pctProfit(-0.01098902)
                                        .profit(Money.of(-20, "USD"))
                                        .currentPrice(Money.of(1800, "USD"))
                                        .currentValue(Money.of(1800, "USD"))
                                        .build())))
                .investedBalance(Money.of(2*1800 + 1820, "USD"))
                .currentValue(Money.of(2*1800+1850, "USD"))
                .pctProfit(-0.99446495)
                .profit(Money.of(30, "USD"))
                .build();

        System.out.println(aggregatedPortfolioJson);
//        Assertions.assertThat(expectedAggregatedPortfolio).isEqualTo(aggregatedPortfolioJson);
    }
}
