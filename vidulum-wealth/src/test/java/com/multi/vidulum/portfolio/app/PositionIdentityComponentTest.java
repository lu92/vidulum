package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telling two rows of the same ticker apart (task C15).
 *
 * <p>A portfolio onboarded from an exchange always produces two: the part the exchange priced, and
 * the part that arrived from elsewhere with no price at all. The split has existed since C2 in the
 * model, the database and the merge rules — and stopped at the API, where both rows came back
 * called "BTC" and nothing else. The only way to identify one was to guess from whether it carried
 * a cost, and that guess dies the moment the owner supplies one.
 */
class PositionIdentityComponentTest {

    private static final Broker BROKER = Broker.of("OKX");
    private static final Currency EUR = Currency.of("EUR");

    private final PortfolioSummaryMapper mapper = new PortfolioSummaryMapper(new QuoteRestClient() {
        @Override
        public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
            return AssetPriceMetadata.builder()
                    .symbol(symbol)
                    .currentPrice(symbol.getOrigin().equals(symbol.getDestination())
                            ? Price.one(symbol.getDestination().getId())
                            : Price.of(70_000, symbol.getDestination().getId()))
                    .build();
        }

        @Override
        public AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker) {
            return AssetBasicInfo.notFound(ticker);
        }

        @Override
        public void registerBasicInfoAboutAsset(Broker broker, AssetBasicInfo assetBasicInfo) {
        }
    });

    @Test
    void shouldSayWhichPositionEachRowIs() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .with("BTC", Quantity.of(0.3),
                        CostBasis.of(Quantity.of(0.3), Price.of(50_000, "EUR"), Provenance.EXCHANGE_REPORTED))
                .withUnknownCost("BTC", Quantity.of(1))
                .withCash("EUR", Quantity.of(4_000))
                .build();

        List<PortfolioDto.AssetSummaryJson> assets = mapper.map(portfolio, EUR).getAssets();

        assertThat(assets).extracting(
                        PortfolioDto.AssetSummaryJson::getTicker,
                        PortfolioDto.AssetSummaryJson::getSubName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("BTC", "traded"),
                        org.assertj.core.groups.Tuple.tuple("BTC", "transferred-in"),
                        org.assertj.core.groups.Tuple.tuple("EUR", "none"));
    }

    /**
     * Cash is {@code SubName.none()}, whose name is the empty string — a blank cell in every
     * interface, and indistinguishable from a field nobody filled in. It is named on the way out.
     */
    @Test
    void shouldNameCashRatherThanLeavingItBlank() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withCash("EUR", Quantity.of(1_000))
                .build();

        assertThat(mapper.map(portfolio, EUR).getAssets())
                .singleElement()
                .satisfies(cash -> assertThat(cash.getSubName()).isEqualTo("none"));
    }

    /**
     * The identity survives the owner supplying a cost for the transferred-in part — which is
     * exactly where the old workaround of guessing by cost stopped working.
     */
    @Test
    void shouldStillTellThemApartOnceBothRowsHaveACost() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .with("BTC", Quantity.of(0.3),
                        CostBasis.of(Quantity.of(0.3), Price.of(50_000, "EUR"), Provenance.EXCHANGE_REPORTED))
                .at(com.multi.vidulum.common.SubName.transferredIn(), "BTC", Quantity.of(1),
                        CostBasis.of(Quantity.of(1), Price.of(25_000, "EUR"), Provenance.USER_PROVIDED))
                .build();

        List<PortfolioDto.AssetSummaryJson> assets = mapper.map(portfolio, EUR).getAssets();

        assertThat(assets).allSatisfy(row -> assertThat(row.getCostBasis()).isNotNull());
        assertThat(assets).extracting(PortfolioDto.AssetSummaryJson::getSubName)
                .containsExactlyInAnyOrder("traded", "transferred-in");
    }
}
