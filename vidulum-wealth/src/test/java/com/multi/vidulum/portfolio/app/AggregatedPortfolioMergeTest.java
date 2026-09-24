package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Segment;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as the same holding when several portfolios are shown as one (task C6).
 *
 * <p>The view merged on ticker alone. C2 had split a holding by where it came from — bought here,
 * or transferred in from somewhere we cannot price — because the two carry different facts, and
 * merging on ticker put them straight back together. The merged line then claimed a single average
 * price over a quantity that price never covered, and it was stamped {@code SubName.none()}, so
 * nothing in the result revealed that anything had been merged.
 */
class AggregatedPortfolioMergeTest {

    private static final Broker OKX = Broker.of("OKX");
    private static final Broker KRAKEN = Broker.of("KRAKEN");
    private static final Segment CRYPTO = Segment.of("crypto");

    private AggregatedPortfolio aggregate() {
        return AggregatedPortfolio.builder()
                .userId(UserId.of("U10000001"))
                .segmentedAssets(new HashMap<>())
                .build();
    }

    private static Asset asset(String ticker, SubName subName, double quantity, CostBasis costBasis) {
        return Asset.builder()
                .ticker(Ticker.of(ticker))
                .subName(subName)
                .costBasis(costBasis)
                .quantity(Quantity.of(quantity))
                .locked(Quantity.zero("Number"))
                .free(Quantity.of(quantity))
                .activeLocks(new HashSet<>())
                .build();
    }

    private static CostBasis cost(double quantity, double price) {
        return CostBasis.of(Quantity.of(quantity), Price.of(price, "EUR"), Provenance.EXCHANGE_REPORTED);
    }

    private List<Asset> assetsOf(AggregatedPortfolio aggregated, Broker broker) {
        Map<Broker, List<Asset>> perBroker = aggregated.fetchSegmentedAssets().get(CRYPTO);
        return perBroker.get(broker);
    }

    /**
     * Two lines of bitcoin from one portfolio: one bought, one transferred in. They stay two.
     */
    @Test
    void shouldKeepAHoldingBoughtApartFromOneTransferredIn() {
        AggregatedPortfolio aggregated = aggregate();

        aggregated.addAssets(CRYPTO, OKX, List.of(
                asset("BTC", SubName.traded(), 0.3, cost(0.3, 50_000)),
                asset("BTC", SubName.transferredIn(), 2, null)));

        List<Asset> assets = assetsOf(aggregated, OKX);

        assertThat(assets).hasSize(2);
        assertThat(assets).extracting(Asset::getSubName)
                .as("the old code merged these and stamped the result none()")
                .containsExactlyInAnyOrder(SubName.traded(), SubName.transferredIn());

        Asset traded = assets.stream().filter(a -> SubName.traded().equals(a.getSubName())).findFirst().orElseThrow();
        assertThat(traded.getQuantity()).isEqualTo(Quantity.of(0.3));
        assertThat(traded.getCostBasis()).isEqualTo(cost(0.3, 50_000));

        Asset transferredIn = assets.stream()
                .filter(a -> SubName.transferredIn().equals(a.getSubName())).findFirst().orElseThrow();
        assertThat(transferredIn.getQuantity()).isEqualTo(Quantity.of(2));
        assertThat(transferredIn.hasKnownCost())
                .as("no price was ever known for it, and merging must not invent one")
                .isFalse();
    }

    /** The same position held in two portfolios at the same broker is genuinely one line. */
    @Test
    void shouldMergeTheSamePositionAcrossPortfolios() {
        AggregatedPortfolio aggregated = aggregate();

        aggregated.addAssets(CRYPTO, OKX, List.of(asset("BTC", SubName.traded(), 1, cost(1, 40_000))));
        aggregated.addAssets(CRYPTO, OKX, List.of(asset("BTC", SubName.traded(), 1, cost(1, 60_000))));

        List<Asset> assets = assetsOf(aggregated, OKX);

        assertThat(assets).singleElement().satisfies(merged -> {
            assertThat(merged.getQuantity()).isEqualTo(Quantity.of(2));
            assertThat(merged.getSubName()).isEqualTo(SubName.traded());
            assertThat(merged.getCostBasis().avgPrice().getAmount().doubleValue())
                    .as("50 000 over two units, both of them priced")
                    .isEqualTo(50_000);
            assertThat(merged.getCostBasis().quantity()).isEqualTo(Quantity.of(2));
        });
    }

    /**
     * Merging a priced line with an unpriced one keeps the average over the units it actually
     * covers — the quantity grows, the price does not move.
     */
    @Test
    void shouldNotLetAnUnpricedLineDragTheAverage() {
        AggregatedPortfolio aggregated = aggregate();

        aggregated.addAssets(CRYPTO, OKX, List.of(asset("BTC", SubName.traded(), 1, cost(1, 50_000))));
        aggregated.addAssets(CRYPTO, OKX, List.of(asset("BTC", SubName.traded(), 3, null)));

        Asset merged = assetsOf(aggregated, OKX).get(0);

        assertThat(merged.getQuantity()).isEqualTo(Quantity.of(4));
        assertThat(merged.getCostBasis().avgPrice().getAmount().doubleValue()).isEqualTo(50_000);
        assertThat(merged.getCostBasis().quantity())
                .as("one unit of four is priced, and the cost says so")
                .isEqualTo(Quantity.of(1));
    }

    /** Brokers are never merged into each other: the same ticker at two venues is two holdings. */
    @Test
    void shouldKeepBrokersApart() {
        AggregatedPortfolio aggregated = aggregate();

        aggregated.addAssets(CRYPTO, OKX, List.of(asset("BTC", SubName.traded(), 1, cost(1, 50_000))));
        aggregated.addAssets(CRYPTO, KRAKEN, List.of(asset("BTC", SubName.traded(), 2, cost(2, 30_000))));

        assertThat(assetsOf(aggregated, OKX)).singleElement()
                .satisfies(a -> assertThat(a.getQuantity()).isEqualTo(Quantity.of(1)));
        assertThat(assetsOf(aggregated, KRAKEN)).singleElement()
                .satisfies(a -> assertThat(a.getQuantity()).isEqualTo(Quantity.of(2)));
    }

    /** Cash keeps its own key too, so it never absorbs a traded position of the same ticker. */
    @Test
    void shouldKeepCashApartFromATradedPositionOfTheSameTicker() {
        AggregatedPortfolio aggregated = aggregate();

        aggregated.addAssets(CRYPTO, OKX, List.of(
                asset("EUR", SubName.none(), 5_000, CostBasis.atPar(Quantity.of(5_000), "EUR")),
                asset("EUR", SubName.transferredIn(), 1_000, null)));

        assertThat(assetsOf(aggregated, OKX)).hasSize(2);
    }
}
