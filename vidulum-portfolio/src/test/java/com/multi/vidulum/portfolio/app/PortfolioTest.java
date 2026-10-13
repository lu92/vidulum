package com.multi.vidulum.portfolio.app;


import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.portfolio.domain.portfolio.RealisedResult;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionId;
import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.CannotUnlockAssetException;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioEvents;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import com.multi.vidulum.portfolio.domain.portfolio.Contribution;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for Portfolio aggregate — no Spring context, no Testcontainers.
 * Uses {@link InMemoryPortfolioRepository} with entity round-trip.
 */
@Slf4j
class PortfolioTest {
    /** Contributions take their moment and identity from the caller now (C9). */
    private static final java.time.ZonedDateTime FIXED_CONTRIBUTION_TIME =
            java.time.ZonedDateTime.parse("2022-01-01T00:00:00Z");


    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneId.of("UTC"));
    private final PortfolioFactory portfolioFactory = new PortfolioFactory();
    private final DomainPortfolioRepository portfolioRepository = new InMemoryPortfolioRepository(clock);

    private static final UserId USER_ID = new UserId("U10000001");
    private static final Broker BROKER = Broker.of("Broker");
    private static final Currency USD = Currency.of("USD");
    private static final Currency EUR = Currency.of("EUR");
    private static final String PORTFOLIO_NAME = "XYZ";
    private static final ZonedDateTime DATE_TIME = ZonedDateTime.parse("2021-06-01T06:30:00Z");
    private static final OrderId ORDER_ID = OrderId.of("order-id-1");
    private static final OrderId ORDER_ID_2 = OrderId.of("order-id-2");
    private static final OrderId ORDER_ID_3 = OrderId.of("order-id-3");

    @Test
    public void shouldOpenEmptyPortfolioTest() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME);
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
                                .costBasis(CostBasis.of(Quantity.of(10000), Price.one("USD"), Provenance.ASSUMED_PAR))
                                .quantity(Quantity.of(10000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(10000))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .contributions(List.of(Contribution.paidIn(ContributionId.of("deposit-1"), Money.of(10000.0, "USD"), ZonedDateTime.parse("2022-01-01T00:00:00Z"))))
                .realisedResults(List.of())
                .appliedTrades(Set.of())
                .allowedDepositCurrency(Currency.of("USD"))
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
                                Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME)
                );
    }

    @Test
    public void shouldBuyBitcoin() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME);
        portfolio.lockAsset(Ticker.of("USD"), ORDER_ID, Quantity.of(4000), DATE_TIME);
        portfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-1"))
                        .orderId(ORDER_ID)
                        .symbol(Symbol.of("BTC/USD"))
                        .subName(SubName.none())
                        .side(Side.BUY)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "USD"))
                        .dateTime(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
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
                                .costBasis(CostBasis.of(Quantity.of(6000), Price.one("USD"), Provenance.ASSUMED_PAR))
                                .quantity(Quantity.of(6000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(6000))
                                .activeLocks(Set.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .subName(SubName.traded())
                                .costBasis(CostBasis.of(Quantity.of(0.1), Price.of(40000.0, "USD"), Provenance.DERIVED_FROM_FILLS))
                                .quantity(Quantity.of(0.1))
                                .locked(Quantity.zero())
                                .free(Quantity.of(0.1))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .contributions(List.of(Contribution.paidIn(ContributionId.of("deposit-1"), Money.of(10000.0, "USD"), ZonedDateTime.parse("2022-01-01T00:00:00Z"))))
                .realisedResults(List.of())
                .appliedTrades(Set.of(TradeId.of("trade-1")))
                .allowedDepositCurrency(Currency.of("USD"))
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
                                Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
                                SubName.none(),
                                ORDER_ID,
                                Quantity.of(4000),
                                DATE_TIME
                        ),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-1"),
                                ORDER_ID,
                                Symbol.of("BTC/USD"),
                                SubName.none(),
                                Side.BUY,
                                Quantity.of(0.1),
                                Price.of(40000.0, "USD")
                        ,
                        ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                );
    }

    @Test
    public void shouldBuyAndSellTest() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME);
        portfolio.lockAsset(Ticker.of("USD"), ORDER_ID, Quantity.of(4000), DATE_TIME);
        portfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-1"))
                        .orderId(ORDER_ID)
                        .symbol(Symbol.of("BTC/USD"))
                        .subName(SubName.none())
                        .side(Side.BUY)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "USD"))
                        .dateTime(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                .build());
        portfolio.lockAsset(Ticker.of("BTC"), ORDER_ID_2, Quantity.of(0.1), DATE_TIME);
        portfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-2"))
                        .orderId(ORDER_ID_2)
                        .symbol(Symbol.of("BTC/USD"))
                        .subName(SubName.none())
                        .side(Side.SELL)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "USD"))
                        .dateTime(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
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
                                .costBasis(CostBasis.of(Quantity.of(10000), Price.one("USD"), Provenance.ASSUMED_PAR))
                                .quantity(Quantity.of(10000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(10000))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .contributions(List.of(Contribution.paidIn(ContributionId.of("deposit-1"), Money.of(10000.0, "USD"), ZonedDateTime.parse("2022-01-01T00:00:00Z"))))
                // The sale closed at what it cost, so the settled result is zero — a number,
                // not a silence, and that distinction is the whole of F6.
                .realisedResults(List.of(new RealisedResult(
                        TradeId.of("trade-2"), ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                        Ticker.of("BTC"), SubName.traded(), Quantity.of(0.1), Quantity.of(0.1),
                        Money.of(4000.0000, "USD"), Money.of(4000.0000, "USD"))))
                .appliedTrades(Set.of(TradeId.of("trade-1"), TradeId.of("trade-2")))
                .allowedDepositCurrency(Currency.of("USD"))
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
                                Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
                                SubName.none(),
                                ORDER_ID,
                                Quantity.of(4000),
                                DATE_TIME
                        ),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-1"),
                                ORDER_ID,
                                Symbol.of("BTC/USD"),
                                SubName.none(),
                                Side.BUY,
                                Quantity.of(0.1),
                                Price.of(40000.0, "USD")
                        ,
                        ZonedDateTime.parse("2022-01-01T00:00:00Z")),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
                                SubName.traded(),
                                ORDER_ID_2,
                                Quantity.of(0.1),
                                DATE_TIME
                        ),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-2"),
                                ORDER_ID_2,
                                Symbol.of("BTC/USD"),
                                SubName.none(),
                                Side.SELL,
                                Quantity.of(0.1),
                                Price.of(40000.0, "USD"),
                        ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                );
    }

    @Test
    public void shouldLockAndUnlockAssetTest() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME);
        portfolio.lockAsset(Ticker.of("USD"), ORDER_ID, Quantity.of(4000), DATE_TIME);
        portfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-1"))
                        .orderId(ORDER_ID)
                        .symbol(Symbol.of("BTC/USD"))
                        .subName(SubName.none())
                        .side(Side.BUY)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "USD"))
                        .dateTime(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                .build());

        portfolio.lockAsset(Ticker.of("BTC"), ORDER_ID_2, Quantity.of(0.03), DATE_TIME);
        portfolio.lockAsset(Ticker.of("USD"), ORDER_ID_3, Quantity.of(2000), DATE_TIME);
        portfolio.unlockAsset(Ticker.of("BTC"), ORDER_ID_2, Quantity.of(0.015), DATE_TIME);
        portfolio.unlockAsset(Ticker.of("USD"), ORDER_ID_3, Quantity.of(700), DATE_TIME);

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
                                .costBasis(CostBasis.of(Quantity.of(6000), Price.one("USD"), Provenance.ASSUMED_PAR))
                                .quantity(Quantity.of(6000))
                                .locked(Quantity.of(1300))
                                .free(Quantity.of(4700))
                                .activeLocks(Set.of(new Asset.AssetLock(ORDER_ID_3, Quantity.of(1300))))
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .subName(SubName.traded())
                                .costBasis(CostBasis.of(Quantity.of(0.1), Price.of(40000.0, "USD"), Provenance.DERIVED_FROM_FILLS))
                                .quantity(Quantity.of(0.1))
                                .locked(Quantity.of(0.015))
                                .free(Quantity.of(0.085))
                                .activeLocks(Set.of(new Asset.AssetLock(ORDER_ID_2, Quantity.of(0.015))))
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .contributions(List.of(Contribution.paidIn(ContributionId.of("deposit-1"), Money.of(10000.0, "USD"), ZonedDateTime.parse("2022-01-01T00:00:00Z"))))
                .realisedResults(List.of())
                .appliedTrades(Set.of(TradeId.of("trade-1")))
                .allowedDepositCurrency(Currency.of("USD"))
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
                                Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
                                SubName.none(),
                                ORDER_ID,
                                Quantity.of(4000),
                                DATE_TIME
                        ),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-1"),
                                ORDER_ID,
                                Symbol.of("BTC/USD"),
                                SubName.none(),
                                Side.BUY,
                                Quantity.of(0.1),
                                Price.of(40000.0, "USD")
                        ,
                        ZonedDateTime.parse("2022-01-01T00:00:00Z")),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
                                SubName.traded(),
                                ORDER_ID_2,
                                Quantity.of(0.03),
                                DATE_TIME
                        ),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
                                SubName.none(),
                                ORDER_ID_3,
                                Quantity.of(2000),
                                DATE_TIME
                        ),
                        new PortfolioEvents.AssetUnlockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
                                SubName.none(),
                                ORDER_ID_3,
                                Quantity.of(700),
                                DATE_TIME
                        ),
                        new PortfolioEvents.AssetUnlockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
                                SubName.traded(),
                                ORDER_ID_2,
                                Quantity.of(0.015),
                                DATE_TIME
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
                BROKER,
                USD
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
                .contributions(List.of())
                .realisedResults(List.of())
                .appliedTrades(Set.of())
                .allowedDepositCurrency(Currency.of("USD"))
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

    @Test
    public void shouldWithdrawAllMoneyTest() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME);
        portfolio.withdrawMoney(Money.of(10000, "USD"), ContributionId.of("withdrawal-1"), FIXED_CONTRIBUTION_TIME);
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
                                .costBasis(CostBasis.of(Quantity.of(0), Price.one("USD"), Provenance.ASSUMED_PAR))
                                .quantity(Quantity.of(0))
                                .locked(Quantity.zero())
                                .free(Quantity.of(0))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                // Saldo wraca do zera, ale rejestr pamieta oba ruchy - o to chodzi w C9:
                // "nic nie mam" i "nic nie wplacilem" to dwa rozne zdania.
                .contributions(List.of(
                        Contribution.paidIn(ContributionId.of("deposit-1"), Money.of(10000.0, "USD"), FIXED_CONTRIBUTION_TIME),
                        Contribution.takenOut(ContributionId.of("withdrawal-1"), Money.of(10000.0, "USD"), FIXED_CONTRIBUTION_TIME)))
                .realisedResults(List.of())
                .appliedTrades(Set.of())
                .allowedDepositCurrency(Currency.of("USD"))
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
                                Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME),
                        new PortfolioEvents.MoneyWithdrawEvent(
                                portfolio.getPortfolioId(),
                                Money.of(10000, "USD"), ContributionId.of("withdrawal-1"), FIXED_CONTRIBUTION_TIME)
                );
    }

    @Test
    public void shouldBuyAndSellWithOtherCurrencyTest() {
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER,
                EUR
        );

        portfolio.depositMoney(Money.of(10000, "EUR"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME);
        Portfolio persistedPortfolio = portfolioRepository.save(portfolio);
        persistedPortfolio.lockAsset(Ticker.of("EUR"), ORDER_ID, Quantity.of(4000), DATE_TIME);
        persistedPortfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-1"))
                        .orderId(ORDER_ID)
                        .symbol(Symbol.of("BTC/EUR"))
                        .subName(SubName.none())
                        .side(Side.BUY)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "EUR"))
                        .dateTime(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                .build());
        persistedPortfolio.lockAsset(Ticker.of("BTC"), ORDER_ID_2, Quantity.of(0.1), DATE_TIME);
        persistedPortfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-2"))
                        .orderId(ORDER_ID_2)
                        .symbol(Symbol.of("BTC/EUR"))
                        .subName(SubName.none())
                        .side(Side.SELL)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "EUR"))
                        .dateTime(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                .build());

        Portfolio savedPortfolio = portfolioRepository.save(persistedPortfolio);

        assertThat(savedPortfolio).isEqualTo(Portfolio.builder()
                .portfolioId(portfolio.getPortfolioId())
                .userId(USER_ID)
                .name(PORTFOLIO_NAME)
                .broker(BROKER)
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("EUR"))
                                .subName(SubName.none())
                                .costBasis(CostBasis.of(Quantity.of(10000), Price.one("EUR"), Provenance.ASSUMED_PAR))
                                .quantity(Quantity.of(10000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(10000))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .contributions(List.of(Contribution.paidIn(ContributionId.of("deposit-1"), Money.of(10000.0, "EUR"), ZonedDateTime.parse("2022-01-01T00:00:00Z"))))
                // The sale closed at what it cost, so the settled result is zero — a number,
                // not a silence, and that distinction is the whole of F6.
                .realisedResults(List.of(new RealisedResult(
                        TradeId.of("trade-2"), ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                        Ticker.of("BTC"), SubName.traded(), Quantity.of(0.1), Quantity.of(0.1),
                        Money.of(4000.0000, "EUR"), Money.of(4000.0000, "EUR"))))
                .appliedTrades(Set.of(TradeId.of("trade-1"), TradeId.of("trade-2")))
                .allowedDepositCurrency(Currency.of("EUR"))
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
                                Money.of(10000, "EUR"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("EUR"),
                                SubName.none(),
                                ORDER_ID,
                                Quantity.of(4000),
                                DATE_TIME
                        ),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-1"),
                                ORDER_ID,
                                Symbol.of("BTC/EUR"),
                                SubName.none(),
                                Side.BUY,
                                Quantity.of(0.1),
                                Price.of(40000.0, "EUR")
                        ,
                        ZonedDateTime.parse("2022-01-01T00:00:00Z")),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
                                SubName.traded(),
                                ORDER_ID_2,
                                Quantity.of(0.1),
                                DATE_TIME
                        ),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-2"),
                                ORDER_ID_2,
                                Symbol.of("BTC/EUR"),
                                SubName.none(),
                                Side.SELL,
                                Quantity.of(0.1),
                                Price.of(40000.0, "EUR"),
                        ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                );
    }

    @Test
    public void cannotUnlockAssetForOrderWhichIsNotPresent() {
        // Given
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER,
                USD
        );
        portfolio.depositMoney(Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME);
        portfolio.lockAsset(Ticker.of("USD"), ORDER_ID, Quantity.of(4000), DATE_TIME);

        // When and Then
        assertThatThrownBy(() -> portfolio.unlockAsset(Ticker.of("USD"), OrderId.of("Unknown order-id"), Quantity.of(1), DATE_TIME))
                .isInstanceOf(CannotUnlockAssetException.class)
                .hasMessage("Cannot unlock [Ticker(Id=USD)] - unable to find [OrderId(id=Unknown order-id)]");
    }

    @Test
    public void cannotUnlockAssetCauseOfInsufficientBalance() {
        // Given
        PortfolioId portfolioId = PortfolioId.generate();
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                PORTFOLIO_NAME,
                USER_ID,
                BROKER,
                USD
        );
        portfolio.depositMoney(Money.of(10000, "USD"), ContributionId.of("deposit-1"), FIXED_CONTRIBUTION_TIME);
        portfolio.lockAsset(Ticker.of("USD"), ORDER_ID, Quantity.of(4000), DATE_TIME);

        // When and Then
        assertThatThrownBy(() -> portfolio.unlockAsset(Ticker.of("USD"), ORDER_ID, Quantity.of(5000), DATE_TIME))
                .isInstanceOf(CannotUnlockAssetException.class)
                .hasMessage("Cannot unlock [Ticker(Id=USD)] for [OrderId(id=order-id-1)] - insufficient balance [Quantity(qty=5000.0, unit=Number)]");
    }
}