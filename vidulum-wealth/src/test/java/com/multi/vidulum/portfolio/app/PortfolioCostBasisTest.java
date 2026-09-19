package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import org.junit.jupiter.api.Test;

import static com.multi.vidulum.portfolio.app.PortfolioFixture.portfolio;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code CostBasis} changes about a portfolio, at component level — no Spring, no
 * containers, state stated directly through {@link PortfolioFixture}.
 *
 * <p>These are the cases the existing tests could not express, because no production path
 * produces a position without a cost: everything reachable through deposits and trades has a
 * price attached. That is exactly the state an exchange snapshot will arrive in.
 */
class PortfolioCostBasisTest {

    private static Asset only(Portfolio portfolio) {
        return portfolio.getAssets().getFirst();
    }

    @Test
    void shouldReportNoCostForAPositionTransferredInFromOutside() {
        Portfolio portfolio = portfolio()
                .withUnknownCost("BTC", Quantity.of(1))
                .build();

        Asset btc = only(portfolio);

        assertThat(btc.hasKnownCost()).isFalse();
        assertThat(btc.knownCost()).isEmpty();
        assertThat(btc.coveredQuantity()).isEqualTo(Quantity.zero("Number"));
    }

    /**
     * The shape of the bug this type exists to prevent: the cost covers 0.3 of the 100 held, and
     * the known cost must be 0.3 × price — not 100 × price.
     */
    @Test
    void shouldCostOnlyTheCoveredPartOfAPartiallyKnownPosition() {
        Portfolio portfolio = portfolio()
                .with("USDC", Quantity.of(100),
                        CostBasis.of(Quantity.of(0.3), Price.of(0.99, "USD"), Provenance.EXCHANGE_REPORTED))
                .build();

        Asset usdc = only(portfolio);

        assertThat(usdc.coveredQuantity()).isEqualTo(Quantity.of(0.3));
        assertThat(usdc.knownCost()).contains(Money.of(0.297, "USD"));
    }

    @Test
    void shouldSurviveTheRepositoryRoundTripIncludingTheAbsenceOfCost() {
        Portfolio portfolio = portfolio()
                .withUnknownCost("BTC", Quantity.of(1))
                .with("ETH", Quantity.of(2),
                        CostBasis.of(Quantity.of(2), Price.of(2000, "USD"), Provenance.USER_PROVIDED))
                .build();

        Portfolio restored = Portfolio.from(portfolio.getSnapshot());

        assertThat(restored.getAssets())
                .usingRecursiveComparison()
                .isEqualTo(portfolio.getAssets());
        assertThat(restored.getAssets().getFirst().getCostBasis()).isNull();
        assertThat(restored.getAssets().getLast().getCostBasis().provenance())
                .isEqualTo(Provenance.USER_PROVIDED);
    }

    /**
     * Cash is taken at par and says so, instead of carrying a bare 1 that reads like a real price.
     */
    @Test
    void shouldRecordDepositedCashAtPar() {
        Portfolio portfolio = portfolio().denominatedIn("USD").build();

        portfolio.depositMoney(Money.of(10_000, "USD"));

        Asset cash = only(portfolio);
        assertThat(cash.getCostBasis().provenance()).isEqualTo(Provenance.ASSUMED_PAR);
        assertThat(cash.getCostBasis().quantity()).isEqualTo(Quantity.of(10_000));
        assertThat(cash.knownCost()).contains(Money.of(10_000, "USD"));
    }

    /**
     * Withdrawing shrinks the position, so the cost has to shrink with it — otherwise it keeps
     * claiming to cover units that are no longer held.
     */
    @Test
    void shouldShrinkTheCostWhenCashIsWithdrawn() {
        Portfolio portfolio = portfolio().denominatedIn("USD").build();
        portfolio.depositMoney(Money.of(10_000, "USD"));

        portfolio.withdrawMoney(Money.of(4_000, "USD"));

        Asset cash = only(portfolio);
        assertThat(cash.getQuantity()).isEqualTo(Quantity.of(6_000));
        assertThat(cash.getCostBasis().quantity()).isEqualTo(Quantity.of(6_000));
        assertThat(cash.knownCost()).contains(Money.of(6_000, "USD"));
    }
}
