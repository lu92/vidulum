package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.AmbiguousAssetSelectionException;
import com.multi.vidulum.portfolio.domain.AssetNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.portfolio.domain.NotSufficientBalance;
import com.multi.vidulum.portfolio.domain.DuplicateAssetPositionException;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static com.multi.vidulum.portfolio.app.PortfolioFixture.portfolio;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two positions of one ticker living side by side — the state an exchange snapshot produces when
 * part of a holding was bought through us and part arrived from outside.
 *
 * <p>None of this is reachable through deposits and trades, which is why it needs
 * {@link PortfolioFixture}. What is being checked is not the split itself but everything the
 * split used to break silently: locks landing on an arbitrary position, and trades creating a
 * third one.
 */
class PortfolioSplitPositionsTest {
    /** Contributions take their moment and identity from the caller now (C9). */
    private static final java.time.ZonedDateTime FIXED_CONTRIBUTION_TIME =
            java.time.ZonedDateTime.parse("2022-01-01T00:00:00Z");


    private static final ZonedDateTime NOW = ZonedDateTime.parse("2021-06-01T06:30:00Z");
    private static final OrderId ORDER = OrderId.of("order-1");
    private static final CostBasis BOUGHT =
            CostBasis.of(Quantity.of(0.3), Price.of(50_000, "USD"), Provenance.DERIVED_FROM_FILLS);

    private static Portfolio splitPortfolio() {
        return portfolio()
                .with("BTC", Quantity.of(0.3), BOUGHT)
                .withUnknownCost("BTC", Quantity.of(1))
                .build();
    }

    private static Asset position(Portfolio portfolio, SubName subName) {
        return portfolio.getAssets().stream()
                .filter(asset -> asset.getSubName().equals(subName))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldHoldTheSameTickerInTwoPositionsWithSeparateCosts() {
        Portfolio portfolio = splitPortfolio();

        assertThat(portfolio.findAssetsByTicker(Ticker.of("BTC"))).hasSize(2);
        assertThat(position(portfolio, SubName.traded()).knownCost())
                .contains(Money.of(15_000, "USD"));
        assertThat(position(portfolio, SubName.transferredIn()).knownCost()).isEmpty();
    }

    /**
     * The corruption C2 removes: {@code findFirst()} used to pick a position by list order, so a
     * lock could land on the transferred-in units while the matching unlock looked for it on the
     * traded ones.
     */
    @Test
    void shouldRefuseToLockWithoutSayingWhichPosition() {
        Portfolio portfolio = splitPortfolio();

        assertThatThrownBy(() -> portfolio.lockAsset(Ticker.of("BTC"), ORDER, Quantity.of(0.1), NOW))
                .isInstanceOf(AmbiguousAssetSelectionException.class)
                .hasMessageContaining("BTC")
                .hasMessageContaining("traded")
                .hasMessageContaining("transferred-in");

        assertThat(position(portfolio, SubName.traded()).getLocked()).isEqualTo(Quantity.zero());
        assertThat(position(portfolio, SubName.transferredIn()).getLocked()).isEqualTo(Quantity.zero());
    }

    @Test
    void shouldLockAndUnlockExactlyThePositionItWasToldTo() {
        Portfolio portfolio = splitPortfolio();

        portfolio.lockAsset(Ticker.of("BTC"), SubName.transferredIn(), ORDER, Quantity.of(0.4), NOW);

        assertThat(position(portfolio, SubName.transferredIn()).getLocked()).isEqualTo(Quantity.of(0.4));
        assertThat(position(portfolio, SubName.transferredIn()).getFree()).isEqualTo(Quantity.of(0.6));
        assertThat(position(portfolio, SubName.traded()).getLocked())
                .as("the other position must be untouched")
                .isEqualTo(Quantity.zero());

        portfolio.unlockAsset(Ticker.of("BTC"), SubName.transferredIn(), ORDER, Quantity.of(0.4), NOW);

        assertThat(position(portfolio, SubName.transferredIn()).getLocked()).isEqualTo(Quantity.zero());
        assertThat(position(portfolio, SubName.transferredIn()).getFree()).isEqualTo(Quantity.of(1));
    }

    @Test
    void shouldStillResolveTheOnlyPositionWhenThereIsNoAmbiguity() {
        Portfolio portfolio = portfolio()
                .with("BTC", Quantity.of(0.3), BOUGHT)
                .build();

        portfolio.lockAsset(Ticker.of("BTC"), ORDER, Quantity.of(0.1), NOW);

        assertThat(position(portfolio, SubName.traded()).getLocked()).isEqualTo(Quantity.of(0.1));
    }

    @Test
    void shouldReportAnUnknownPositionAsAssetNotFound() {
        Portfolio portfolio = splitPortfolio();

        assertThatThrownBy(() -> portfolio.lockAsset(
                Ticker.of("BTC"), SubName.of("nope"), ORDER, Quantity.of(0.1), NOW))
                .isInstanceOf(AssetNotFoundException.class);
    }

    /**
     * Cash is never split by origin, so deposits and withdrawals stay unambiguous no matter how
     * many crypto positions the portfolio holds.
     */
    @Test
    void shouldKeepCashUnambiguousAlongsideSplitPositions() {
        Portfolio portfolio = portfolio()
                .denominatedIn("USD")
                .with("BTC", Quantity.of(0.3), BOUGHT)
                .withUnknownCost("BTC", Quantity.of(1))
                .build();

        portfolio.depositMoney(Money.of(10_000, "USD"), "deposit-1", FIXED_CONTRIBUTION_TIME);
        portfolio.withdrawMoney(Money.of(4_000, "USD"), "withdrawal-1", FIXED_CONTRIBUTION_TIME);

        Asset cash = position(portfolio, SubName.none());
        assertThat(cash.getTicker()).isEqualTo(Ticker.of("USD"));
        assertThat(cash.getQuantity()).isEqualTo(Quantity.of(6_000));
        assertThat(portfolio.findAssetsByTicker(Ticker.of("BTC"))).hasSize(2);
    }

    @Test
    void shouldSurviveTheRepositoryRoundTripWithBothPositions() {
        Portfolio portfolio = splitPortfolio();

        Portfolio restored = Portfolio.from(portfolio.getSnapshot());

        assertThat(restored.getAssets())
                .usingRecursiveComparison()
                .isEqualTo(portfolio.getAssets());
        assertThat(restored.findAssetsByTicker(Ticker.of("BTC")))
                .extracting(asset -> asset.getSubName().getName())
                .containsExactly("traded", "transferred-in");
    }

    private static void trade(Portfolio portfolio, Side side, Quantity quantity, double price, String tradeId) {
        portfolio.handleExecutedTrade(ExecutedTrade.builder()
                .portfolioId(portfolio.getPortfolioId())
                .tradeId(TradeId.of(tradeId))
                .orderId(ORDER)
                .symbol(Symbol.of("BTC/USD"))
                .subName(SubName.none())
                .side(side)
                .quantity(quantity)
                .price(Price.of(price, "USD"))
                .build());
    }

    private static Portfolio splitPortfolioWithCash() {
        return portfolio()
                .denominatedIn("USD")
                .withCash("USD", Quantity.of(100_000))
                .with("BTC", Quantity.of(0.3), BOUGHT)
                .withUnknownCost("BTC", Quantity.of(1))
                .build();
    }

    /**
     * The flow that matters most: buying more of an asset we also hold from outside. The purchase
     * must land on {@code traded} and merge with the cost already there — and the transferred-in
     * units must not be drawn into the average, because their cost is unknown, not zero.
     */
    @Test
    void shouldAddABuyToTheTradedPositionWithoutTouchingTheTransferredInOne() {
        Portfolio portfolio = splitPortfolioWithCash();

        trade(portfolio, Side.BUY, Quantity.of(0.1), 60_000, "trade-buy");

        assertThat(portfolio.findAssetsByTicker(Ticker.of("BTC")))
                .as("a buy must not create a third position")
                .hasSize(2);

        Asset traded = position(portfolio, SubName.traded());
        assertThat(traded.getQuantity()).isEqualTo(Quantity.of(0.4));
        // 0.3 x 50 000 + 0.1 x 60 000 = 21 000 over 0.4 units
        assertThat(traded.knownCost()).contains(Money.of(21_000, "USD"));
        assertThat(traded.getCostBasis().quantity()).isEqualTo(Quantity.of(0.4));

        Asset transferredIn = position(portfolio, SubName.transferredIn());
        assertThat(transferredIn.getQuantity()).isEqualTo(Quantity.of(1));
        assertThat(transferredIn.hasKnownCost()).isFalse();
    }

    @Test
    void shouldTakeASellFromTheTradedPositionOnly() {
        Portfolio portfolio = splitPortfolioWithCash();

        trade(portfolio, Side.SELL, Quantity.of(0.1), 70_000, "trade-sell");

        // Compared with a tolerance: Quantity is backed by a double, so 0.3 - 0.1 lands on
        // 0.19999999999999998. That is F2's problem, not this test's.
        assertThat(position(portfolio, SubName.traded()).getQuantity().getQty())
                .isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(position(portfolio, SubName.transferredIn()).getQuantity()).isEqualTo(Quantity.of(1));
    }

    /**
     * Records today's behaviour rather than endorsing it: a sale larger than the traded position
     * is refused even though the portfolio holds more of the ticker elsewhere. Selling units whose
     * cost is unknown is a taxable event we cannot compute, and C7 owns that decision - until
     * then, refusing is the safe answer.
     */
    @Test
    void shouldRefuseToSellMoreThanTheTradedPositionHoldsEvenWhenMoreIsHeldElsewhere() {
        Portfolio portfolio = splitPortfolioWithCash();

        assertThatThrownBy(() -> trade(portfolio, Side.SELL, Quantity.of(0.9), 70_000, "trade-big"))
                .isInstanceOf(NotSufficientBalance.class);

        assertThat(position(portfolio, SubName.traded()).getQuantity()).isEqualTo(Quantity.of(0.3));
        assertThat(position(portfolio, SubName.transferredIn()).getQuantity()).isEqualTo(Quantity.of(1));
    }

    @Test
    void shouldRefuseToUnlockWithoutSayingWhichPosition() {
        Portfolio portfolio = splitPortfolio();
        portfolio.lockAsset(Ticker.of("BTC"), SubName.traded(), ORDER, Quantity.of(0.1), NOW);

        assertThatThrownBy(() -> portfolio.unlockAsset(Ticker.of("BTC"), ORDER, Quantity.of(0.1), NOW))
                .isInstanceOf(AmbiguousAssetSelectionException.class);

        assertThat(position(portfolio, SubName.traded()).getLocked())
                .as("a refused unlock must leave the lock in place")
                .isEqualTo(Quantity.of(0.1));
    }

    /**
     * The invariant everything else rests on: one position per (ticker, subName).
     */
    @Test
    void shouldRefuseTwoPositionsUnderTheSameName() {
        assertThatThrownBy(() -> portfolio()
                .with("BTC", Quantity.of(0.3), BOUGHT)
                .with("BTC", Quantity.of(0.2), BOUGHT)
                .build())
                .isInstanceOf(DuplicateAssetPositionException.class)
                .hasMessageContaining("BTC")
                .hasMessageContaining("traded");
    }

    /**
     * A stablecoin can plausibly be held as cash and as a transferred-in position at once. Cash
     * operations must still find the cash one.
     */
    @Test
    void shouldFindCashEvenWhenTheSameCurrencyIsAlsoHeldAsATransferredInPosition() {
        Portfolio portfolio = portfolio()
                .denominatedIn("USD")
                .withCash("USD", Quantity.of(1_000))
                .withUnknownCost("USD", Quantity.of(500))
                .build();

        portfolio.depositMoney(Money.of(200, "USD"), "deposit-1", FIXED_CONTRIBUTION_TIME);

        assertThat(position(portfolio, SubName.none()).getQuantity()).isEqualTo(Quantity.of(1_200));
        assertThat(position(portfolio, SubName.transferredIn()).getQuantity()).isEqualTo(Quantity.of(500));
    }

    @Test
    void shouldCloseAPortfolioHoldingBothPositions() {
        Portfolio portfolio = splitPortfolio();

        portfolio.close();

        assertThat(portfolio.isOpened()).isFalse();
        assertThat(portfolio.findAssetsByTicker(Ticker.of("BTC"))).hasSize(2);
    }
}
