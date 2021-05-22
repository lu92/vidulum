package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.quotation.app.QuoteRestController;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import com.multi.vidulum.trading.app.TradingRestController;
import com.multi.vidulum.trading.domain.DomainTradeRepository;
import com.multi.vidulum.trading.infrastructure.TradeMongoRepository;
import com.multi.vidulum.user.app.UserDto;
import com.multi.vidulum.user.app.UserRestController;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
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

import java.util.List;
import java.util.Optional;

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

    @Test
    void shouldReturnCustomersWithRatingGreater90AsVIP() throws InterruptedException {

        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "ETH", "USD", 2850, "USD", 1.09);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);

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

        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .money(Money.of(100000.0, "USD"))
                        .build());


        tradingRestController.makeTrade(PortfolioDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .side(BUY)
                .quantity(0.1)
                .price(Money.of(60000.0, "USD"))
                .build());

        tradingRestController.makeTrade(PortfolioDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .side(BUY)
                .quantity(0.1)
                .price(Money.of(30000, "USD"))
                .build());

        tradingRestController.makeTrade(PortfolioDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .side(BUY)
                .quantity(0.1)
                .price(Money.of(30000, "USD"))
                .build());

        tradingRestController.makeTrade(PortfolioDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .side(SELL)
                .quantity(0.1)
                .price(Money.of(40000, "USD"))
                .build());

//
//        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
//                .originTradeId("trade4")
//                .portfolioId(registeredPortfolio.getPortfolioId())
//                .symbol("ETH/USD")
//                .side(BUY)
//                .quantity(0.75)
//                .price(Money.of(2800, "USD"))
//                .build());
//
//        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
//                .originTradeId("trade4")
//                .portfolioId(registeredPortfolio.getPortfolioId())
//                .symbol("ETH/USD")
//                .side(BUY)
//                .quantity(0.25)
//                .price(Money.of(2800, "USD"))
//                .build());
//
//        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
//                .originTradeId("trade4")
//                .portfolioId(registeredPortfolio.getPortfolioId())
//                .symbol("ETH/USD")
//                .side(BUY)
//                .quantity(0.5)
//                .price(Money.of(3400, "USD"))
//                .build());
//
//        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
//                .originTradeId("trade4")
//                .portfolioId(registeredPortfolio.getPortfolioId())
//                .symbol("ETH/USD")
//                .side(SELL)
//                .quantity(0.2)
//                .price(Money.of(3000, "USD"))
//                .build());


//   *************
//        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
//                .originTradeId("trade4")
//                .portfolioId(registeredPortfolio.getPortfolioId())
//                .symbol("ETH/USD")
//                .side(SELL)
//                .quantity(0.2)
//                .price(Money.of(3000, "USD"))
//                .build());

//        tradingRestController.makeTrade(PortfolioDto.TradeExecutedJson.builder()
//                .originTradeId("trade4")
//                .portfolioId(registeredPortfolio.getPortfolioId())
//                .userId(persistedUser.getUserId())
//                .symbol("ETH/USD")
//                .side(SELL)
//                .quantity(0.2)
//                .price(Money.of(3000, "USD"))
//                .build());


//        **************

        Thread.sleep(10000);

        PortfolioDto.PortfolioSummaryJson retrievedPortfolio = portfolioRestController.getPortfolio(registeredPortfolio.getPortfolioId());

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
                                .fullName("")
                                .avgPurchasePrice(Money.one("USD"))
                                .quantity(92000.0)
                                .tags(List.of("currency", "USD"))
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .fullName("Not found")
                                .avgPurchasePrice(Money.of(40000, "USD"))
                                .quantity(0.20000000000000004)
                                .tags(List.of())
                                .build()
                ))
                .investedBalance(Money.of(100000.0, "USD"))
                .build();

        Assertions.assertThat(portfolio).isEqualTo(expectedPortfolio);
    }
}
