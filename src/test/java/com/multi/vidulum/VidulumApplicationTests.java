package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.app.PnlDto;
import com.multi.vidulum.pnl.app.PnlRestController;
import com.multi.vidulum.pnl.domain.*;
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
import com.multi.vidulum.risk_management.app.RiskManagementDto;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                .build());

        Awaitility.await().atMost(10, SECONDS).until(() -> appliedTradesOnPortfolioNumber.longValue() == 1);

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
                                .locked(Quantity.zero())
                                .free(Quantity.zero())
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
                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(100000.0, "USD"))
                .totalProfit(Money.zero("USD"))
                .pctProfit(0)
                .build();

        assertThat(aggregatedPortfolio).isEqualTo(expectedAggregatedPortfolio);
        pnlRestController.makePnlSnapshot(
                PnlDto.MakePnlSnapshotJson.builder()
                        .userId(createdUserJson.getUserId())
                        .from(ZonedDateTime.parse("2021-06-01T00:00:00Z"))
                        .to(ZonedDateTime.parse("2021-06-01T23:59:59Z"))
                        .build());

        PnlDto.PnlHistoryJson pnlHistory = pnlRestController.getPnlHistory(createdUserJson.getUserId());
        System.out.println(pnlHistory);

        TradingDto.OrderSummaryJson placedOrder = tradingRestController.placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-X")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .targetPrice(Money.of(70000, "USD"))
                        .entryPrice(Money.of(60000, "USD"))
                        .stopLoss(Money.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        List<TradingDto.OrderSummaryJson> allOpenedOrders = tradingRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        assertThat(allOpenedOrders)
                .usingElementComparatorIgnoringFields("orderId")
                .containsExactly(
                        TradingDto.OrderSummaryJson.builder()
                                .originOrderId("origin trade-id-X")
                                .portfolioId(registeredPortfolio.getPortfolioId())
                                .symbol("BTC/USD")
                                .type(OrderType.OCO)
                                .side(SELL)
                                .status(Status.OPEN)
                                .targetPrice(Money.of(70000, "USD"))
                                .entryPrice(Money.of(60000, "USD"))
                                .stopLoss(Money.of(55000, "USD"))
                                .quantity(Quantity.of(0.5))
                                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                                .build()
                );

        TradingDto.OrderSummaryJson canceledOrder = tradingRestController.cancelOrder(placedOrder.getOriginOrderId());

        assertThat(List.of(canceledOrder))
                .usingElementComparatorIgnoringFields("orderId")
                .containsExactly(
                        TradingDto.OrderSummaryJson.builder()
                                .originOrderId("origin trade-id-X")
                                .portfolioId(registeredPortfolio.getPortfolioId())
                                .symbol("BTC/USD")
                                .type(OrderType.OCO)
                                .side(SELL)
                                .status(Status.CANCELLED)
                                .targetPrice(Money.of(70000, "USD"))
                                .entryPrice(Money.of(60000, "USD"))
                                .stopLoss(Money.of(55000, "USD"))
                                .quantity(Quantity.of(0.5))
                                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                                .build()
                );
        assertThat(tradingRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        TradingDto.OrderSummaryJson placedOrder2 = tradingRestController.placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .targetPrice(Money.of(70000, "USD"))
                        .entryPrice(Money.of(60000, "USD"))
                        .stopLoss(Money.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        TradingDto.OrderExecutionSummaryJson orderExecutionSummaryJson = tradingRestController.executeOrder(
                TradingDto.ExecuteOrderJson.builder()
                        .originTradeId("origin trade-id-Y")
                        .originOrderId("origin order-id-Y")
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        assertThat(orderExecutionSummaryJson).isEqualTo(
                TradingDto.OrderExecutionSummaryJson.builder()
                        .originTradeId("origin trade-id-Y")
                        .originOrderId("origin order-id-Y")
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .quantity(Quantity.of(0.5))
                        .profit(Money.of(5000, "USD"))
                        .build());

        assertThat(tradingRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        Awaitility.await().atMost(10, SECONDS).until(() -> appliedTradesOnPortfolioNumber.longValue() == 2);

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio2 = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId());

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregatedPortfolio2 = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(createdUserJson.getUserId())
                .segmentedAssets(Map.of(
                        "Crypto", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("BTC")
                                        .fullName("Bitcoin")
                                        .avgPurchasePrice(Money.of(50000.0, "USD"))
                                        .quantity(Quantity.of(0.5))
                                        .pctProfit(0.2)
                                        .profit(Money.of(5000, "USD"))
                                        .currentPrice(Money.of(60000.0, "USD"))
                                        .currentValue(Money.of(30000.0, "USD"))
                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                        .build()),
                        "Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Money.one("USD"))
                                        .quantity(Quantity.of(75000.0))
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Money.of(1, "USD"))
                                        .currentValue(Money.of(75000.0, "USD"))
                                        .tags(List.of())
                                        .build())))
                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(105000.0, "USD"))
                .totalProfit(Money.of(5000, "USD"))
                .pctProfit(0.05)
                .build();

        assertThat(aggregatedPortfolio2).isEqualTo(expectedAggregatedPortfolio2);
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

        Awaitility.await().atMost(5, SECONDS).until(() -> {
            UserId existingUserId = UserId.of(createdUserJson.getUserId());
            return pnlRepository.findByUser(existingUserId).isPresent();
        });

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
                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(120000.0, "USD"))
                .totalProfit(Money.of(20000.0, "USD"))
                .pctProfit(0.2)
                .build();
        assertThat(aggregatedPortfolio).isEqualTo(expectedAggregagedPortfolio);

        PortfolioDto.OpenedPositionsJson openedPositions = portfolioRestController.getOpenedPositions(registeredPortfolio.getPortfolioId());
        assertThat(openedPositions).isEqualTo(PortfolioDto.OpenedPositionsJson.builder()
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

        Awaitility.await().atMost(5, SECONDS).until(() -> {
            UserId existingUserId = UserId.of(createdUserJson.getUserId());
            return pnlRepository.findByUser(existingUserId).isPresent();
        });

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
            log.info("Following trade applied to portfolio [{}]", event);
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
                .quantity(Quantity.of(0.1))
                .price(Money.of(60000.0, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade2")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.1))
                .price(Money.of(30000, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade3")
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
                .originTradeId("trade5")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.75))
                .price(Money.of(2800, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade6")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.25))
                .price(Money.of(2800, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade7")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.5))
                .price(Money.of(3400, "USD"))
                .build());

        tradingRestController.makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade8")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(0.2))
                .price(Money.of(3000, "USD"))
                .build());

        Awaitility.await().atMost(15, SECONDS).until(() -> appliedTradesOnPortfolioNumber.longValue() == 8);

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
                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
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
                .quantity(Quantity.of(0.1))
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
                        .quantity(Quantity.of(0.1))
                        .risk(Money.of(500, "USD"))
                        .reward(Money.of(1000, "USD"))
                        .riskRewardRatio(RiskRewardRatio.of(1, 2))
                        .value(Money.of(0.1 * 60000, "USD"))
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

        RiskManagementDto.RiskManagementStatementJson riskManagementStatement = riskManagementRestController.getRiskManagementStatement(registeredPortfolio.getPortfolioId());
        System.out.println(jsonFormatter.formatToPrettyJson(riskManagementStatement));

//        assertThat(riskManagementStatement.getAssetRiskManagementStatements()).containsExactlyInAnyOrder(
//
//                RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                        .ticker("USD")
//                        .quantity(Quantity.of(88100))
//                        .stopLosses(List.of())
//                        .avgPurchasePrice(Money.of(1, "USD"))
//                        .currentValue(Money.of(88100, "USD"))
//                        .currentPrice(Money.of(1, "USD"))
//                        .safeMoney(Money.of(88100, "USD"))
//                        .riskMoney(Money.zero("USD"))
//                        .ragStatus(RagStatus.GREEN)
//                        .pctRiskOfPortfolio(0)
//                        .build(),
//                RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                        .ticker("BTC")
//                        .quantity(Quantity.of(0.20000000000000004))
//                        .stopLosses(List.of(
//                                RiskManagementDto.StopLossJson.builder()
//                                        .symbol("BTC/USD")
//                                        .quantity(Quantity.of(0.1))
//                                        .price(Money.of(55000, "USD"))
//                                        .isApplicable(true)
//                                        .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                        .build()
//                        ))
//                        .avgPurchasePrice(Money.of(40000, "USD"))
//                        .currentValue(Money.of(12000, "USD"))
//                        .currentPrice(Money.of(60000, "USD"))
//                        .safeMoney(Money.of(5499.999999999998200000000, "USD"))
//                        .riskMoney(Money.of(6500.000000000001800000000, "USD"))
//                        .ragStatus(RagStatus.GREEN)
//                        .pctRiskOfPortfolio(14.96999999)
//                        .build(),
//                RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                        .ticker("ETH")
//                        .quantity(Quantity.of(1.3))
//                        .stopLosses(List.of(
//                                RiskManagementDto.StopLossJson.builder()
//                                        .symbol("ETH/USD")
//                                        .quantity(Quantity.of(0.5))
//                                        .price(Money.of(2700, "USD"))
//                                        .isApplicable(true)
//                                        .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                        .build(),
//                                RiskManagementDto.StopLossJson.builder()
//                                        .symbol("ETH/USD")
//                                        .quantity(Quantity.of(0.5))
//                                        .price(Money.of(2900, "USD"))
//                                        .isApplicable(false)
//                                        .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                        .build()
//                        ))
//                        .avgPurchasePrice(Money.of(3000, "USD"))
//                        .currentValue(Money.of(3705, "USD"))
//                        .currentPrice(Money.of(2850, "USD"))
//                        .safeMoney(Money.of(1350, "USD"))
//                        .riskMoney(Money.of(2355,"USD"))
//                        .ragStatus(RagStatus.GREEN)
//                        .pctRiskOfPortfolio(43.07855626)
//                        .build()
//        );
//
//        assertThat(riskManagementStatement).isEqualTo(
//                RiskManagementDto.RiskManagementStatementJson.builder()
//                        .portfolioId(registeredPortfolio.getPortfolioId())
//                        .userId(registeredPortfolio.getUserId())
//                        .name(registeredPortfolio.getName())
//                        .broker(registeredPortfolio.getBroker())
//                        .assetRiskManagementStatements(
//                                List.of(
//                                        RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                                                .ticker("USD")
//                                                .quantity(Quantity.of(88100))
//                                                .stopLosses(List.of())
//                                                .avgPurchasePrice(Money.of(1, "USD"))
//                                                .currentValue(Money.of(88100, "USD"))
//                                                .currentPrice(Money.of(1, "USD"))
//                                                .safeMoney(Money.of(88100, "USD"))
//                                                .riskMoney(Money.zero("USD"))
//                                                .ragStatus(RagStatus.GREEN)
//                                                .pctRiskOfPortfolio(0)
//                                                .build(),
//                                        RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                                                .ticker("BTC")
//                                                .quantity(Quantity.of(0.20000000000000004))
//                                                .stopLosses(List.of(
//                                                        RiskManagementDto.StopLossJson.builder()
//                                                                .symbol("BTC/USD")
//                                                                .quantity(Quantity.of(0.1))
//                                                                .price(Money.of(55000, "USD"))
//                                                                .isApplicable(true)
//                                                                .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                                                .build()
//                                                ))
//                                                .avgPurchasePrice(Money.of(40000, "USD"))
//                                                .currentValue(Money.of(12000, "USD"))
//                                                .currentPrice(Money.of(60000, "USD"))
//                                                .safeMoney(Money.of(5499.999999999998200000000, "USD"))
//                                                .riskMoney(Money.of(6500.000000000001800000000, "USD"))
//                                                .ragStatus(RagStatus.GREEN)
//                                                .pctRiskOfPortfolio(14.96999999)
//                                                .build(),
//                                        RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                                                .ticker("ETH")
//                                                .quantity(Quantity.of(1.3))
//                                                .stopLosses(List.of(
//                                                        RiskManagementDto.StopLossJson.builder()
//                                                                .symbol("ETH/USD")
//                                                                .quantity(Quantity.of(0.5))
//                                                                .price(Money.of(2700, "USD"))
//                                                                .isApplicable(true)
//                                                                .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                                                .build(),
//                                                        RiskManagementDto.StopLossJson.builder()
//                                                                .symbol("ETH/USD")
//                                                                .quantity(Quantity.of(0.5))
//                                                                .price(Money.of(2900, "USD"))
//                                                                .isApplicable(false)
//                                                                .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                                                .build()
//                                                ))
//                                                .avgPurchasePrice(Money.of(3000, "USD"))
//                                                .currentValue(Money.of(3705, "USD"))
//                                                .currentPrice(Money.of(2850, "USD"))
//                                                .safeMoney(Money.of(1350, "USD"))
//                                                .riskMoney(Money.of(2355,"USD"))
//                                                .ragStatus(RagStatus.GREEN)
//                                                .pctRiskOfPortfolio(43.07855626)
//                                                .build()
//                                ))
//                        .investedBalance(portfolio.getInvestedBalance())
//                        .currentValue(Money.of(103805, "USD"))
//                        .profit(Money.of(3805, "USD"))
//                        .safe(Money.of(94949.999999999998200000000, "USD"))
//                        .risk(Money.of(8855.000000000001800000000, "USD"))
//                        .pctProfit(-0.96195)
//                        .riskPct(-12.72275551)
//                        .build()
//        );

    }

    @Test
    void shouldPersistPortfolioForPreciousMetals() {
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

        Awaitility.await().atMost(5, SECONDS).until(() -> {
            UserId existingUserId = UserId.of(createdUserJson.getUserId());
            return pnlRepository.findByUser(existingUserId).isPresent();
        });

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
                .portfolioIds(List.of(registeredPreciousMetalsPortfolio.getPortfolioId(), registeredPreciousMetalsPortfolio2.getPortfolioId()))
                .investedBalance(Money.of(7230, "USD"))
                .currentValue(Money.of(7285, "USD"))
                .totalProfit(Money.of(55, "USD"))
                .pctProfit(0.00760719)
                .build();

        assertThat(expectedAggregatedPortfolio).isEqualTo(aggregatedPortfolioJson);

        pnlRestController.makePnlSnapshot(
                PnlDto.MakePnlSnapshotJson.builder()
                        .userId(createdUserJson.getUserId())
                        .from(ZonedDateTime.parse("2021-02-01T00:00:00Z"))
                        .to(ZonedDateTime.parse("2021-06-01T00:00:00Z"))
                        .build());

        PnlDto.PnlHistoryJson pnlHistoryJson = pnlRestController.getPnlHistory(createdUserJson.getUserId());
        System.out.println(pnlHistoryJson);

        System.out.println(jsonFormatter.formatToPrettyJson(pnlHistoryJson));
        assertThat(pnlHistoryJson.getUserId()).isEqualTo(createdUserJson.getUserId());
        assertThat(pnlHistoryJson.getPnlStatements()).hasSize(1);
        assertThat(pnlHistoryJson.getPnlStatements())
                .usingElementComparatorIgnoringFields("dateTime", "portfolioStatements")
                .isEqualTo(List.of(
                        PnlDto.PnlStatementJson.builder()
                                .investedBalance(Money.of(7230, "USD"))
                                .currentValue(Money.of(7285, "USD"))
                                .totalProfit(Money.of(55, "USD"))
                                .pctProfit(0.00760719)
                                .build()));

        PnlDto.PnlStatementJson pnlStatementJson = pnlHistoryJson.getPnlStatements().get(0);
        assertThat(pnlStatementJson.getPortfolioStatements()).hasSize(2);
        assertThat(pnlStatementJson.getPortfolioStatements())
                .usingElementComparatorIgnoringFields("executedTrades")
                .isEqualTo(List.of(
                        PnlDto.PnlPortfolioStatementJson.builder()
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .investedBalance(Money.of(5420, "USD"))
                                .currentValue(Money.of(5475, "USD"))
                                .totalProfit(Money.of(55, "USD"))
                                .pctProfit(-0.9898524)
                                .build(),
                        PnlDto.PnlPortfolioStatementJson.builder()
                                .portfolioId(registeredPreciousMetalsPortfolio2.getPortfolioId())
                                .investedBalance(Money.of(1810, "USD"))
                                .currentValue(Money.of(1810, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(-1.0)
                                .build()
                ));

        assertThat(pnlStatementJson.getPortfolioStatements().get(0).getExecutedTrades()).hasSize(4);

        // find portfolio-statement for first portfolio
        PnlDto.PnlPortfolioStatementJson statementOfFirstPortfolio = pnlHistoryJson.getPnlStatements().get(0).getPortfolioStatements().stream()
                .filter(pnlPortfolioStatementJson -> pnlPortfolioStatementJson.getPortfolioId().equals(registeredPreciousMetalsPortfolio.getPortfolioId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("portfolio-statement is missing"));

        assertThat(statementOfFirstPortfolio.getExecutedTrades())
                .usingElementComparatorIgnoringFields("tradeId")
                .isEqualTo(List.of(
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade1")
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .symbol("XAU/USD")
                                .subName("Maple Leaf")
                                .side(BUY)
                                .quantity(Quantity.of(2, "oz"))
                                .price(Money.of(1800, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-02-01T06:24:11Z"))
                                .build(),
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade2")
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .symbol("XAU/USD")
                                .subName("Krugerrand")
                                .side(BUY)
                                .quantity(Quantity.of(1, "oz"))
                                .price(Money.of(1820, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-03-02T12:14:11Z"))
                                .build(),
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade3")
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .symbol("XAU/USD")
                                .subName("Maple Leaf")
                                .side(SELL)
                                .quantity(Quantity.of(1, "oz"))
                                .price(Money.of(1850, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-04-01T16:24:11Z"))
                                .build(),
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade4")
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .symbol("XAG/USD")
                                .subName("Maple Leaf")
                                .side(BUY)
                                .quantity(Quantity.of(5, "oz"))
                                .price(Money.of(90, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-03-02T12:14:11Z"))
                                .build()));

        // find portfolio-statement for second portfolio
        PnlDto.PnlPortfolioStatementJson statementOfSecondPortfolio = pnlHistoryJson.getPnlStatements().get(0).getPortfolioStatements().stream()
                .filter(pnlPortfolioStatementJson -> pnlPortfolioStatementJson.getPortfolioId().equals(registeredPreciousMetalsPortfolio2.getPortfolioId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("portfolio-statement is missing"));


        assertThat(statementOfSecondPortfolio.getExecutedTrades())
                .usingElementComparatorIgnoringFields("tradeId")
                .isEqualTo(List.of(
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade5")
                                .portfolioId(registeredPreciousMetalsPortfolio2.getPortfolioId())
                                .symbol("XAU/USD")
                                .subName("Maple Leaf")
                                .side(BUY)
                                .quantity(Quantity.of(1, "oz"))
                                .price(Money.of(1800, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-02-01T06:24:11Z"))
                                .build()));
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
                                .pnlPortfolioStatements(List.of(
                                        PnlPortfolioStatement.builder()
                                                .portfolioId(PortfolioId.of(UUID.randomUUID().toString()))
                                                .investedBalance(Money.of(100, "USD"))
                                                .currentValue(Money.of(120, "USD"))
                                                .totalProfit(Money.of(20, "USD"))
                                                .pctProfit(20)
                                                .executedTrades(List.of(
                                                        PnlTradeDetails.builder()
                                                                .tradeId(TradeId.of(UUID.randomUUID().toString()))
                                                                .originTradeId(OriginTradeId.of("T1"))
                                                                .portfolioId(PortfolioId.of("P123"))
                                                                .symbol(Symbol.of("BTC/USD"))
                                                                .subName(SubName.of("crypto"))
                                                                .side(BUY)
                                                                .quantity(Quantity.of(0.5))
                                                                .price(Money.of(30000, "USD"))
                                                                .originDateTime(ZonedDateTime.parse("2021-07-01T06:30:00Z"))
                                                                .build(),
                                                        PnlTradeDetails.builder()
                                                                .tradeId(TradeId.of(UUID.randomUUID().toString()))
                                                                .originTradeId(OriginTradeId.of("T2"))
                                                                .portfolioId(PortfolioId.of("P123"))
                                                                .symbol(Symbol.of("BTC/USD"))
                                                                .subName(SubName.of("crypto"))
                                                                .side(SELL)
                                                                .quantity(Quantity.of(0.4))
                                                                .price(Money.of(35000, "USD"))
                                                                .originDateTime(ZonedDateTime.parse("2021-07-01T07:30:00Z"))
                                                                .build()
                                                ))
                                                .build()))
                                .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                                .build(),
                        PnlStatement.builder()
                                .investedBalance(Money.of(100, "USD"))
                                .currentValue(Money.of(130, "USD"))
                                .totalProfit(Money.of(30, "USD"))
                                .pctProfit(30)
                                .pnlPortfolioStatements(List.of(
                                        PnlPortfolioStatement.builder()
                                                .portfolioId(PortfolioId.of(UUID.randomUUID().toString()))
                                                .investedBalance(Money.of(100, "USD"))
                                                .currentValue(Money.of(130, "USD"))
                                                .totalProfit(Money.of(30, "USD"))
                                                .pctProfit(30)
                                                .executedTrades(List.of(
                                                        PnlTradeDetails.builder()
                                                                .tradeId(TradeId.of(UUID.randomUUID().toString()))
                                                                .originTradeId(OriginTradeId.of("T3"))
                                                                .portfolioId(PortfolioId.of("P123"))
                                                                .symbol(Symbol.of("ETH/USD"))
                                                                .subName(SubName.of("crypto"))
                                                                .side(BUY)
                                                                .quantity(Quantity.of(0.5))
                                                                .price(Money.of(2000, "USD"))
                                                                .originDateTime(ZonedDateTime.parse("2021-07-01T06:30:00Z"))
                                                                .build(),
                                                        PnlTradeDetails.builder()
                                                                .tradeId(TradeId.of(UUID.randomUUID().toString()))
                                                                .originTradeId(OriginTradeId.of("T4"))
                                                                .portfolioId(PortfolioId.of("P123"))
                                                                .symbol(Symbol.of("ETH/USD"))
                                                                .subName(SubName.of("crypto"))
                                                                .side(SELL)
                                                                .quantity(Quantity.of(0.4))
                                                                .price(Money.of(2100, "USD"))
                                                                .originDateTime(ZonedDateTime.parse("2021-07-01T07:30:00Z"))
                                                                .build()
                                                ))
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
