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
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
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

        portfolio.depositMoney(Money.of(10_000, "USD"));
        portfolio.withdrawMoney(Money.of(4_000, "USD"));

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
}
