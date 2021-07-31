package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.pnl.domain.PnlStatement;
import com.multi.vidulum.pnl.domain.PnlTradeDetails;
import com.multi.vidulum.pnl.infrastructure.PnlMongoRepository;
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
import org.testcontainers.shaded.com.fasterxml.jackson.core.JsonProcessingException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();

        assertThat(persistedUser).isEqualTo(expectedUserSummary);

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
        assertThat(optionalPortfolio.isPresent()).isTrue();
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
                                .fullName("Bitcoin")
                                .segment(Segment.of("Crypto"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.of(60000, "USD"))
                                .quantity(Quantity.of(1))
                                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                .build()
                ))
                .investedBalance(Money.of(100000.0, "USD"))
                .build();

        assertThat(portfolio).isEqualTo(expectedPortfolio);
        List<TradingDto.TradeSummaryJson> allTrades = tradingRestController.getAllTrades(createdUserJson.getUserId(), registeredPortfolio.getPortfolioId());
        assertThat(allTrades).hasSize(1);


        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId());

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregatedPortfolio = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(createdUserJson.getUserId())
                .segmentedAssets(Map.of(
                        "Crypto", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("BTC")
                                        .fullName("Bitcoin")
                                        .avgPurchasePrice(Money.of(60000.0, "USD"))
                                        .quantity(Quantity.of(1))
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Money.of(60000.0, "USD"))
                                        .currentValue(Money.of(60000.0, "USD"))
                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                        .build()),
                        "Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Money.one("USD"))
                                        .quantity(Quantity.of(40000.0))
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Money.of(1, "USD"))
                                        .currentValue(Money.of(40000.0, "USD"))
                                        .tags(List.of())
                                        .build())))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(100000.0, "USD"))
                .totalProfit(Money.zero("USD"))
                .pctProfit(0)
                .build();

        assertThat(aggregatedPortfolio).isEqualTo(expectedAggregatedPortfolio);
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
        assertThat(persistedUser).isEqualTo(expectedUserSummary);


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
        assertThat(optionalPortfolio.isPresent()).isTrue();
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

        assertThat(portfolio).isEqualTo(expectedPortfolio);
        List<TradingDto.TradeSummaryJson> allTrades = tradingRestController.getAllTrades(createdUserJson.getUserId(), registeredPortfolio.getPortfolioId());
        assertThat(allTrades).hasSize(2);

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId());

        log.info("Aggregated portfolio:\n {}", jsonFormatter.formatToPrettyJson(aggregatedPortfolio));

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregagedPortfolio = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(createdUserJson.getUserId())
                .segmentedAssets(Map.of(
                        "Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Money.one("USD"))
                                        .quantity(Quantity.of(120000.0))
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Money.of(1, "USD"))
                                        .currentValue(Money.of(120000.0, "USD"))
                                        .tags(List.of())
                                        .build())))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(120000.0, "USD"))
                .totalProfit(Money.of(20000.0, "USD"))
                .pctProfit(0.2)
                .build();
        assertThat(aggregatedPortfolio).isEqualTo(expectedAggregagedPortfolio);

        PortfolioDto.OpenedPositionsJson openedPositions = portfolioRestController.getOpenedPositions(registeredPortfolio.getPortfolioId());
        Assertions.assertThat(openedPositions).isEqualTo(PortfolioDto.OpenedPositionsJson.builder()
                .portfolioId(registeredPortfolio.getPortfolioId())
                .positions(List.of())
                .build());
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
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("BTC")
                .fullName("Bitcoin")
                .segment("Crypto")
                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                .build());
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("ETH")
                .fullName("Ethereum")
                .segment("Crypto")
                .tags(List.of("Ethereum", "Crypto", "ETH"))
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
        assertThat(persistedUser).isEqualTo(expectedUserSummary);

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
        assertThat(optionalPortfolio.isPresent()).isTrue();
        Portfolio portfolio = optionalPortfolio.get();

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
                                .fullName("Bitcoin")
                                .segment(Segment.of("Crypto"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.of(40000, "USD"))
                                .quantity(Quantity.of(0.20000000000000004))
                                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("ETH"))
                                .fullName("Ethereum")
                                .segment(Segment.of("Crypto"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Money.of(3000, "USD"))
                                .quantity(Quantity.of(1.3))
                                .tags(List.of("Ethereum", "Crypto", "ETH"))
                                .build()
                ))
                .investedBalance(Money.of(100000.0, "USD"))
                .build();

        assertThat(portfolio).isEqualTo(expectedPortfolio);
        List<TradingDto.TradeSummaryJson> allTrades = tradingRestController.getAllTrades(createdUserJson.getUserId(), registeredPortfolio.getPortfolioId());
        assertThat(allTrades).hasSize(8);


        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId());
        log.info("Aggregated portfolio:\n{}", jsonFormatter.formatToPrettyJson(aggregatedPortfolio));

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregatedPortfolio = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(createdUserJson.getUserId())
                .segmentedAssets(Map.of("Cash", List.of(
                        PortfolioDto.AssetSummaryJson.builder()
                                .ticker("USD")
                                .fullName("American Dollar")
                                .avgPurchasePrice(Money.one("USD"))
                                .quantity(Quantity.of(88100.0))
                                .pctProfit(0)
                                .profit(Money.of(0, "USD"))
                                .currentPrice(Money.of(1, "USD"))
                                .currentValue(Money.of(88100.0000, "USD"))
                                .tags(List.of())
                                .build()),
                        "Crypto", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("ETH")
                                        .fullName("Ethereum")
                                        .avgPurchasePrice(Money.of(3000.0000, "USD"))
                                        .quantity(Quantity.of(1.3))
                                        .pctProfit(-0.05)
                                        .profit(Money.of(-195.0, "USD"))
                                        .currentPrice(Money.of(2850.0000, "USD"))
                                        .currentValue(Money.of(3705.0000, "USD"))
                                        .tags(List.of("Ethereum", "Crypto", "ETH"))
                                        .build(),
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("BTC")
                                        .fullName("Bitcoin")
                                        .avgPurchasePrice(Money.of(40000.0000, "USD"))
                                        .quantity(Quantity.of(0.20000000000000004))
                                        .pctProfit(0.5)
                                        .profit(Money.of(4000.0000, "USD"))
                                        .currentPrice(Money.of(60000.0000, "USD"))
                                        .currentValue(Money.of(12000.0000, "USD"))
                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                        .build())
                ))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(103805, "USD"))
                .totalProfit(Money.of(3805.0000, "USD"))
                .pctProfit(0.03805)
                .build();

        tradingRestController.placeOrder(TradingDto.PlaceOrderJson.builder()
                .originOrderId("origin trade-id-1")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .symbol("ETH/USD")
                .type(OrderType.OCO)
                .side(SELL)
                .targetPrice(Money.of(3500, "USD"))
                .entryPrice(Money.of(3000, "USD"))
                .stopLoss(Money.of(2700, "USD"))
                .quantity(Quantity.of(0.5))
                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                .build());

        tradingRestController.placeOrder(TradingDto.PlaceOrderJson.builder()
                .originOrderId("origin trade-id-2")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .symbol("BTC/USD")
                .type(OrderType.OCO)
                .side(SELL)
                .targetPrice(Money.of(70000, "USD"))
                .entryPrice(Money.of(60000, "USD"))
                .stopLoss(Money.of(55000, "USD"))
                .quantity(Quantity.of(0.5))
                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                .build());

        tradingRestController.placeOrder(TradingDto.PlaceOrderJson.builder()
                .originOrderId("origin trade-id-3")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .symbol("ETH/USD")
                .type(OrderType.OCO)
                .side(SELL)
                .targetPrice(Money.of(4000, "USD"))
                .entryPrice(Money.of(3000, "USD"))
                .stopLoss(Money.of(2900, "USD"))
                .quantity(Quantity.of(0.5))
                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                .build());


        PortfolioDto.OpenedPositionsJson openedPositions = portfolioRestController.getOpenedPositions(registeredPortfolio.getPortfolioId());

        assertThat(openedPositions.getPortfolioId()).isEqualTo(registeredPortfolio.getPortfolioId());
        assertThat(openedPositions.getPositions()).containsExactlyInAnyOrder(
                PortfolioDto.PositionSummaryJson.builder()
                        .symbol("BTC/USD")
                        .targetPrice(Money.of(70000, "USD"))
                        .entryPrice(Money.of(60000, "USD"))
                        .stopLoss(Money.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .risk(Money.of(2500, "USD"))
                        .reward(Money.of(5000, "USD"))
                        .riskRewardRatio(RiskRewardRatio.of(1, 2))
                        .value(Money.of(0.5 * 60000, "USD"))
                        .pctProfit(0)
                        .build(),
                PortfolioDto.PositionSummaryJson.builder()
                        .symbol("ETH/USD")
                        .targetPrice(Money.of(3750, "USD"))
                        .entryPrice(Money.of(3000, "USD"))
                        .stopLoss(Money.of(2800, "USD"))
                        .quantity(Quantity.of(1))
                        .risk(Money.of(200, "USD"))
                        .reward(Money.of(750, "USD"))
                        .riskRewardRatio(RiskRewardRatio.of(1, 3.75))
                        .value(Money.of(2850, "USD"))
                        .pctProfit(-0.05)
                        .build()
        );

        assertThat(expectedAggregatedPortfolio).isEqualTo(aggregatedPortfolio);

        List<TradingDto.OrderSummaryJson> allOpenedOrders = tradingRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        log.info("[{}]", allOpenedOrders);
    }

    @Test
    void shouldPersistPortfolioForPreciousMetals() throws JsonProcessingException {
        quoteRestController.changePrice("PM", "XAU", "USD", 1800, "USD", 0);
        quoteRestController.changePrice("PM", "XAG", "USD", 95, "USD", 0);
        quoteRestController.changePrice("PM", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("PM", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("XAU")
                .fullName("Gold")
                .segment("Precious Metals")
                .tags(List.of("Gold", "Precious Metals"))
                .build());
        quoteRestController.registerAssetBasicInfo("PM", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("XAG")
                .fullName("Silver")
                .segment("Precious Metals")
                .tags(List.of("Silver", "Precious Metals"))
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

        assertThat(persistedUser).isEqualTo(expectedUserSummary);

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
                        .money(Money.of(2 * 1800 + 1820, "USD"))
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
                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAG/USD")
                .subName("Maple Leaf")
                .side(BUY)
                .quantity(Quantity.of(5, "oz"))
                .price(Money.of(90, "USD"))
                .originDateTime(ZonedDateTime.parse("2021-03-02T12:14:11Z"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade5")
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
                                .quantity(Quantity.of(1850 - 450, "Number"))
                                .tags(List.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("XAG"))
                                .fullName("Silver")
                                .segment(Segment.of("Precious Metals"))
                                .subName(SubName.of("Maple Leaf"))
                                .avgPurchasePrice(Money.of(90, "USD"))
                                .quantity(Quantity.of(5, "oz"))
                                .tags(List.of("Silver", "Precious Metals"))
                                .build()
                ))
                .investedBalance(Money.of(2 * 1800 + 1820, "USD"))
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

        Awaitility.await().atMost(100, SECONDS).until(() -> appliedTradesOnPortfolioNumber.longValue() == 5);

        Optional<Portfolio> optionalPortfolio = portfolioRepository.findById(PortfolioId.of(registeredPreciousMetalsPortfolio.getPortfolioId()));
        assertThat(optionalPortfolio.get()).isEqualTo(expectedPortfolio);

        List<TradingDto.TradeSummaryJson> allTrades = tradingRestController.getAllTrades(createdUserJson.getUserId(), registeredPreciousMetalsPortfolio.getPortfolioId());
        assertThat(allTrades).hasSize(4);

        List<TradingDto.TradeSummaryJson> lastTwoTrades = tradingRestController.getTradesInDateRange(
                createdUserJson.getUserId(),
//                registeredPreciousMetalsPortfolio.getPortfolioId(),
                ZonedDateTime.parse("2021-03-01T00:00:00Z"),
                ZonedDateTime.parse("2021-05-01T00:00:00Z"));
        assertThat(lastTwoTrades).hasSize(3);


        Optional<Portfolio> optionalPortfolio2 = portfolioRepository.findById(PortfolioId.of(registeredPreciousMetalsPortfolio2.getPortfolioId()));
        assertThat(optionalPortfolio2.get()).isEqualTo(expectedPortfolio2);


        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolioJson = portfolioRestController.getAggregatedPortfolio(registeredPreciousMetalsPortfolio.getUserId());

        log.info("Aggregated Portfolio: {}", jsonFormatter.formatToPrettyJson(aggregatedPortfolioJson));

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregatedPortfolio = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(registeredPreciousMetalsPortfolio.getUserId())
                .segmentedAssets(Map.of(
                        "Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Money.one("USD"))
                                        .quantity(Quantity.of(1410))
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Money.of(1, "USD"))
                                        .currentValue(Money.of(1410, "USD"))
                                        .tags(List.of())
                                        .build()),
                        "Precious Metals", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("XAG")
                                        .fullName("Silver")
                                        .avgPurchasePrice(Money.of(90, "USD"))
                                        .quantity(Quantity.of(5, "oz"))
                                        .pctProfit(0.05555555)
                                        .profit(Money.of(25, "USD"))
                                        .currentPrice(Money.of(95, "USD"))
                                        .currentValue(Money.of(475, "USD"))
                                        .tags(List.of("Silver", "Precious Metals"))
                                        .build(),
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("XAU")
                                        .fullName("Gold")
                                        .avgPurchasePrice(Money.of(1790, "USD"))
                                        .quantity(Quantity.of(3, "oz"))
                                        .pctProfit(0.00558659)
                                        .profit(Money.of(30, "USD"))
                                        .currentPrice(Money.of(1800, "USD"))
                                        .currentValue(Money.of(5400, "USD"))
                                        .tags(List.of("Gold", "Precious Metals"))
                                        .build())))
                .investedBalance(Money.of(7230, "USD"))
                .currentValue(Money.of(7285, "USD"))
                .totalProfit(Money.of(55, "USD"))
                .pctProfit(0.00760719)
                .build();

        assertThat(expectedAggregatedPortfolio).isEqualTo(aggregatedPortfolioJson);
    }

    @Test
    public void test() {
        PnlHistory aggregate = PnlHistory.builder()
                .pnlId(null)
                .userId(UserId.of("12345"))
                .pnlStatements(List.of(
                        PnlStatement.builder()
                                .investedBalance(Money.of(100, "USD"))
                                .currentValue(Money.of(120, "USD"))
                                .totalProfit(Money.of(20, "USD"))
                                .pctProfit(20)
                                .executedTrades(List.of(
                                        PnlTradeDetails.builder()
                                                .originTradeId(TradeId.of("T1"))
                                                .portfolioId(PortfolioId.of("P123"))
                                                .symbol(Symbol.of("BTC/USD"))
                                                .subName(SubName.of("crypto"))
                                                .side(BUY)
                                                .quantity(Quantity.of(0.5))
                                                .price(Money.of(30000, "USD"))
                                                .originDateTime(ZonedDateTime.parse("2021-07-01T06:30:00Z"))
                                                .build(),
                                        PnlTradeDetails.builder()
                                                .originTradeId(TradeId.of("T2"))
                                                .portfolioId(PortfolioId.of("P123"))
                                                .symbol(Symbol.of("BTC/USD"))
                                                .subName(SubName.of("crypto"))
                                                .side(SELL)
                                                .quantity(Quantity.of(0.4))
                                                .price(Money.of(35000, "USD"))
                                                .originDateTime(ZonedDateTime.parse("2021-07-01T07:30:00Z"))
                                                .build()
                                ))
                                .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                                .build(),
                        PnlStatement.builder()
                                .investedBalance(Money.of(100, "USD"))
                                .currentValue(Money.of(130, "USD"))
                                .totalProfit(Money.of(30, "USD"))
                                .pctProfit(30)
                                .executedTrades(List.of(
                                        PnlTradeDetails.builder()
                                                .originTradeId(TradeId.of("T3"))
                                                .portfolioId(PortfolioId.of("P123"))
                                                .symbol(Symbol.of("ETH/USD"))
                                                .subName(SubName.of("crypto"))
                                                .side(BUY)
                                                .quantity(Quantity.of(0.5))
                                                .price(Money.of(2000, "USD"))
                                                .originDateTime(ZonedDateTime.parse("2021-07-01T06:30:00Z"))
                                                .build(),
                                        PnlTradeDetails.builder()
                                                .originTradeId(TradeId.of("T4"))
                                                .portfolioId(PortfolioId.of("P123"))
                                                .symbol(Symbol.of("ETH/USD"))
                                                .subName(SubName.of("crypto"))
                                                .side(SELL)
                                                .quantity(Quantity.of(0.4))
                                                .price(Money.of(2100, "USD"))
                                                .originDateTime(ZonedDateTime.parse("2021-07-01T07:30:00Z"))
                                                .build()
                                ))
                                .dateTime(ZonedDateTime.parse("2021-07-01T06:30:00Z"))
                                .build()
                ))
                .build();


        PnlHistory persistedPnlHistory = pnlRepository.save(aggregate);
        Optional<PnlHistory> byUser = pnlRepository.findByUser(UserId.of("12345"));

        System.out.println(persistedPnlHistory);
    }
}
