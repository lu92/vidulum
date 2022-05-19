package com.multi.vidulum.portfolio.app;


import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioEvents;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import com.multi.vidulum.trading.domain.IntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class PortfolioTest extends IntegrationTest {

    private static final UserId USER_ID = UserId.of("User");
    private static final Broker BROKER = Broker.of("Broker");
    private static final Currency USD = Currency.of("USD");
    private static final Currency EUR = Currency.of("EUR");
    private static final String PORTFOLIO_NAME = "XYZ";

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
        portfolio.lockAsset(Ticker.of("USD"), Quantity.of(4000));
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
                                Quantity.of(4000)
                        ),
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
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"));
        portfolio.lockAsset(Ticker.of("USD"), Quantity.of(4000));
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
        portfolio.lockAsset(Ticker.of("BTC"), Quantity.of(0.1));
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
                                Quantity.of(4000)
                        ),
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
                                Quantity.of(0.1)
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
                BROKER,
                USD
        );

        portfolio.depositMoney(Money.of(10000, "USD"));
        portfolio.lockAsset(Ticker.of("USD"), Quantity.of(4000));
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
                                Quantity.of(4000)
                        ),
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
        persistedPortfolio.lockAsset(Ticker.of("EUR"), Quantity.of(4000));
        persistedPortfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-1"))
                        .symbol(Symbol.of("BTC/EUR"))
                        .subName(SubName.none())
                        .side(Side.BUY)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "EUR"))
                        .build());
        persistedPortfolio.lockAsset(Ticker.of("BTC"), Quantity.of(0.1));
        persistedPortfolio.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(portfolio.getPortfolioId())
                        .tradeId(TradeId.of("trade-2"))
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
                                Quantity.of(4000)
                        ),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-1"),
                                Symbol.of("BTC/EUR"),
                                SubName.none(),
                                Side.BUY,
                                Quantity.of(0.1),
                                Price.of(40000.0, "EUR")
                        ),
                        new PortfolioEvents.AssetLockedEvent(
                                portfolio.getPortfolioId(),
                                Ticker.of("BTC"),
                                Quantity.of(0.1)
                        ),
                        new PortfolioEvents.TradeProcessedEvent(
                                portfolio.getPortfolioId(),
                                TradeId.of("trade-2"),
                                Symbol.of("BTC/EUR"),
                                SubName.none(),
                                Side.SELL,
                                Quantity.of(0.1),
                                Price.of(40000.0, "EUR"))
                );
    }
}