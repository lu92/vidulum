package com.multi.vidulum.portfolio.app;


import com.multi.vidulum.FixedClockConfig;
import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.*;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(classes = FixedClockConfig.class)
@Import({PortfolioAppConfig.class})
@Testcontainers
@DirtiesContext
@ExtendWith(SpringExtension.class)
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
class PortfolioTest {

    private static final UserId USER_ID = UserId.of("User");
    private static final Broker BROKER = Broker.of("Broker");
    private static final String PORTFOLIO_NAME = "XYZ";

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.6");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
    }

    @Autowired
    private PortfolioFactory portfolioFactory;

    @Autowired
    private DomainPortfolioRepository portfolioRepository;

    @Test
    public void shouldOpenEmptyPortfolioTest() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER
        );

        portfolio.depositMoney(Money.of(10000, "USD"));
        Portfolio savedPortfolio = portfolioRepository.save(portfolio);

        assertThat(savedPortfolio).isEqualTo(Portfolio.builder()
                .portfolioId(portfolio.getPortfolioId())
                .userId(USER_ID)
                .name(PORTFOLIO_NAME)
                .broker(BROKER)
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(10000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(10000))
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000.0, "USD"))
                .build());

        assertThat(portfolioRepository.findDomainEvents(savedPortfolio.getPortfolioId()))
                .containsExactlyInAnyOrder(
                        new PortfolioEvents.PortfolioOpenedEvent(
                                portfolio.getPortfolioId(),
                                "XYZ",
                                Broker.of("Broker")
                        ),
                        new PortfolioEvents.MoneyDepositedEvent(
                                portfolio.getPortfolioId(),
                                Money.of(10000, "USD"))
                );
    }

    @Test
    public void shouldBuyBitcoin() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER
        );

        portfolio.depositMoney(Money.of(10000, "USD"));
        portfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-1"))
                        .symbol(Symbol.of("BTC/USD"))
                        .subName(SubName.none())
                        .side(Side.BUY)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "USD"))
                        .build());

        Portfolio savedPortfolio = portfolioRepository.save(portfolio);

        assertThat(savedPortfolio).isEqualTo(Portfolio.builder()
                .portfolioId(portfolio.getPortfolioId())
                .userId(USER_ID)
                .name(PORTFOLIO_NAME)
                .broker(BROKER)
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(6000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(6000))
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.of(40000.0, "USD"))
                                .quantity(Quantity.of(0.1))
                                .locked(Quantity.zero())
                                .free(Quantity.of(0.1))
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000.0, "USD"))
                .build());

        assertThat(portfolioRepository.findDomainEvents(savedPortfolio.getPortfolioId()))
                .containsExactlyInAnyOrder(
                        new PortfolioEvents.PortfolioOpenedEvent(
                                portfolio.getPortfolioId(),
                                "XYZ",
                                Broker.of("Broker")
                        ),
                        new PortfolioEvents.MoneyDepositedEvent(
                                portfolio.getPortfolioId(),
                                Money.of(10000, "USD")),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-1"),
                                Symbol.of("BTC/USD"),
                                SubName.none(),
                                Side.BUY,
                                Quantity.of(0.1),
                                Price.of(40000.0, "USD")
                        )
                );
    }

    @Test
    public void shouldBuyAndSellTest() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER
        );

        portfolio.depositMoney(Money.of(10000, "USD"));
        portfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-1"))
                        .symbol(Symbol.of("BTC/USD"))
                        .subName(SubName.none())
                        .side(Side.BUY)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "USD"))
                        .build());
        portfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-2"))
                        .symbol(Symbol.of("BTC/USD"))
                        .subName(SubName.none())
                        .side(Side.SELL)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "USD"))
                        .build());

        Portfolio savedPortfolio = portfolioRepository.save(portfolio);

        assertThat(savedPortfolio).isEqualTo(Portfolio.builder()
                .portfolioId(portfolio.getPortfolioId())
                .userId(USER_ID)
                .name(PORTFOLIO_NAME)
                .broker(BROKER)
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(10000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(10000))
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000.0, "USD"))
                .build());

        assertThat(portfolioRepository.findDomainEvents(savedPortfolio.getPortfolioId()))
                .containsExactlyInAnyOrder(
                        new PortfolioEvents.PortfolioOpenedEvent(
                                portfolio.getPortfolioId(),
                                "XYZ",
                                Broker.of("Broker")
                        ),
                        new PortfolioEvents.MoneyDepositedEvent(
                                portfolio.getPortfolioId(),
                                Money.of(10000, "USD")),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-1"),
                                Symbol.of("BTC/USD"),
                                SubName.none(),
                                Side.BUY,
                                Quantity.of(0.1),
                                Price.of(40000.0, "USD")
                        ),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-2"),
                                Symbol.of("BTC/USD"),
                                SubName.none(),
                                Side.SELL,
                                Quantity.of(0.1),
                                Price.of(40000.0, "USD"))
                );
    }

    @Test
    public void shouldLockAndUnlockAssetTest() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER
        );

        portfolio.depositMoney(Money.of(10000, "USD"));
        portfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-1"))
                        .symbol(Symbol.of("BTC/USD"))
                        .subName(SubName.none())
                        .side(Side.BUY)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "USD"))
                        .build());

        portfolio.lockAsset(Ticker.of("BTC"), Quantity.of(0.03));
        portfolio.lockAsset(Ticker.of("USD"), Quantity.of(2000));
        portfolio.unlockAsset(Ticker.of("USD"), Quantity.of(700));
        portfolio.unlockAsset(Ticker.of("BTC"), Quantity.of(0.015));

        Portfolio savedPortfolio = portfolioRepository.save(portfolio);

        assertThat(savedPortfolio).isEqualTo(Portfolio.builder()
                .portfolioId(portfolio.getPortfolioId())
                .userId(USER_ID)
                .name(PORTFOLIO_NAME)
                .broker(BROKER)
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(6000))
                                .locked(Quantity.of(1300))
                                .free(Quantity.of(4700))
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.of(40000.0, "USD"))
                                .quantity(Quantity.of(0.1))
                                .locked(Quantity.of(0.015))
                                .free(Quantity.of(0.085))
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000.0, "USD"))
                .build());

        assertThat(portfolioRepository.findDomainEvents(savedPortfolio.getPortfolioId()))
                .containsExactlyInAnyOrder(
                        new PortfolioEvents.PortfolioOpenedEvent(
                                portfolio.getPortfolioId(),
                                "XYZ",
                                Broker.of("Broker")
                        ),
                        new PortfolioEvents.MoneyDepositedEvent(
                                portfolio.getPortfolioId(),
                                Money.of(10000, "USD")),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-1"),
                                Symbol.of("BTC/USD"),
                                SubName.none(),
                                Side.BUY,
                                Quantity.of(0.1),
                                Price.of(40000.0, "USD")
                        ),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
                                Quantity.of(0.03)
                        ),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
                                Quantity.of(2000)
                        ),
                        new PortfolioEvents.AssetUnlockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
                                Quantity.of(700)
                        ),
                        new PortfolioEvents.AssetUnlockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
                                Quantity.of(0.015)
                        )
                );
    }

    @Test
    public void shouldClosePortfolio() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER
        );
        portfolio.close();
        Portfolio savedPortfolio = portfolioRepository.save(portfolio);

        assertThat(savedPortfolio).isEqualTo(Portfolio.builder()
                .portfolioId(portfolio.getPortfolioId())
                .userId(USER_ID)
                .name(PORTFOLIO_NAME)
                .broker(BROKER)
                .assets(List.of())
                .status(PortfolioStatus.CLOSED)
                .investedBalance(Money.of(0, "USD"))
                .build());

        assertThat(portfolioRepository.findDomainEvents(savedPortfolio.getPortfolioId()))
                .containsExactlyInAnyOrder(
                        new PortfolioEvents.PortfolioOpenedEvent(
                                portfolio.getPortfolioId(),
                                "XYZ",
                                Broker.of("Broker")
                        ),
                        new PortfolioEvents.PortfolioClosedEvent(
                                portfolio.getPortfolioId(),
                                USER_ID)
                );
    }
}