package com.multi.vidulum.portfolio.app;


import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.CannotUnlockAssetException;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioEvents;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import com.multi.vidulum.trading.domain.IntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Slf4j
class PortfolioTest extends IntegrationTest {

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
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000.0, "USD"))
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
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"));
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
                                .activeLocks(Set.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.of(40000.0, "USD"))
                                .quantity(Quantity.of(0.1))
                                .locked(Quantity.zero())
                                .free(Quantity.of(0.1))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000.0, "USD"))
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
                                Money.of(10000, "USD")),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
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
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"));
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
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000.0, "USD"))
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
                                Money.of(10000, "USD")),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
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
                        ),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
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
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"));
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
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(6000))
                                .locked(Quantity.of(1300))
                                .free(Quantity.of(4700))
                                .activeLocks(Set.of(new Asset.AssetLock(ORDER_ID_3, Quantity.of(1300))))
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.of(40000.0, "USD"))
                                .quantity(Quantity.of(0.1))
                                .locked(Quantity.of(0.015))
                                .free(Quantity.of(0.085))
                                .activeLocks(Set.of(new Asset.AssetLock(ORDER_ID_2, Quantity.of(0.015))))
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000.0, "USD"))
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
                                Money.of(10000, "USD")),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
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
                        ),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
                                ORDER_ID_2,
                                Quantity.of(0.03),
                                DATE_TIME
                        ),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
                                ORDER_ID_3,
                                Quantity.of(2000),
                                DATE_TIME
                        ),
                        new PortfolioEvents.AssetUnlockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("USD"),
                                ORDER_ID_3,
                                Quantity.of(700),
                                DATE_TIME
                        ),
                        new PortfolioEvents.AssetUnlockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
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
                .investedBalance(Money.of(0, "USD"))
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

        portfolio.depositMoney(Money.of(10000, "USD"));
        portfolio.withdrawMoney(Money.of(10000, "USD"));
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
                                .quantity(Quantity.of(0))
                                .locked(Quantity.zero())
                                .free(Quantity.of(0))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.zero("USD"))
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
                                Money.of(10000, "USD")),
                        new PortfolioEvents.MoneyWithdrawEvent(
                                portfolio.getPortfolioId(),
                                Money.of(10000, "USD"))
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

        portfolio.depositMoney(Money.of(10000, "EUR"));
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
                                .avgPurchasePrice(Price.one("EUR"))
                                .quantity(Quantity.of(10000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(10000))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000.0, "EUR"))
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
                                Money.of(10000, "EUR")),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("EUR"),
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
                        ),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
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
                                Price.of(40000.0, "EUR"))
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
        portfolio.depositMoney(Money.of(10000, "USD"));
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
        portfolio.depositMoney(Money.of(10000, "USD"));
        portfolio.lockAsset(Ticker.of("USD"), ORDER_ID, Quantity.of(4000), DATE_TIME);

        // When and Then
        assertThatThrownBy(() -> portfolio.unlockAsset(Ticker.of("USD"), ORDER_ID, Quantity.of(5000), DATE_TIME))
                .isInstanceOf(CannotUnlockAssetException.class)
                .hasMessage("Cannot unlock [Ticker(Id=USD)] for [OrderId(id=order-id-1)] - insufficient balance [Quantity(qty=5000.0, unit=Number)]");
    }
}