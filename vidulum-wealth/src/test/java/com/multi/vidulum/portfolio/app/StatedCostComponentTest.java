package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.CostReconciliation;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Letting the owner finish what the exchange could not say (task C8).
 *
 * <p>A holding transferred in from elsewhere has no price anybody knows, so its result is withheld
 * (C3) and selling it settles nothing computable (C7). Until now that was permanent outside
 * onboarding: there was no route to supply the missing price, so a portfolio built from a snapshot
 * stayed unanswerable forever — and at a tax office that gap is money, because without a cost
 * there is nothing to deduct from the proceeds.
 */
class StatedCostComponentTest {

    private static final Broker BROKER = Broker.of("OKX");
    private static final Currency EUR = Currency.of("EUR");
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");

    private final Map<String, Double> prices = new HashMap<>();
    private final PortfolioSummaryMapper mapper = new PortfolioSummaryMapper(new QuoteRestClient() {
        @Override
        public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
            if (symbol.getOrigin().equals(symbol.getDestination())) {
                return AssetPriceMetadata.builder()
                        .symbol(symbol).currentPrice(Price.one(symbol.getDestination().getId())).build();
            }
            Double price = prices.get(symbol.getId());
            if (price == null) {
                throw new AssertionError("test published no price for " + symbol.getId());
            }
            return AssetPriceMetadata.builder()
                    .symbol(symbol).currentPrice(Price.of(price, symbol.getDestination().getId())).build();
        }

        @Override
        public AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker) {
            return AssetBasicInfo.notFound(ticker);
        }

        @Override
        public void registerBasicInfoAboutAsset(Broker broker, AssetBasicInfo assetBasicInfo) {
        }
    });

    private Portfolio onboarded() {
        prices.put("BTC/EUR", 70_000.0);
        return PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withUnknownCost("BTC", Quantity.of(1))
                .build();
    }

    private PortfolioDto.AssetSummaryJson row(Portfolio portfolio) {
        return mapper.map(portfolio, EUR).getAssets().getFirst();
    }

    @Test
    void shouldFlagAPositionNobodyHasPricedAsSomethingToFinish() {
        assertThat(row(onboarded()).isAwaitingCost())
                .as("a task an interface can show, not a number a reader has to interpret")
                .isTrue();
    }

    /** Cash is never awaiting a price — asking would be asking somebody to confirm arithmetic. */
    @Test
    void shouldNotAskAboutCash() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withCash("EUR", Quantity.of(4_000))
                .build();

        assertThat(row(portfolio).isAwaitingCost()).isFalse();
    }

    @Test
    void shouldStopAskingOnceTheOwnerHasAnswered() {
        Portfolio portfolio = onboarded();

        portfolio.stateCostOfPosition(
                Ticker.of("BTC"), SubName.transferredIn(), Price.of(25_000, "EUR"), NOW);

        assertThat(row(portfolio).isAwaitingCost()).isFalse();
        assertThat(row(portfolio).getCoverage()).isEqualTo(1.0);
    }

    /** And the answer turns a withheld result into a stated one. */
    @Test
    void shouldMakeTheResultComputableAtLast() {
        Portfolio portfolio = onboarded();
        assertThat(mapper.map(portfolio, EUR).getProfitStatus()).isEqualTo(ProfitStatus.NO_KNOWN_COST);

        portfolio.stateCostOfPosition(
                Ticker.of("BTC"), SubName.transferredIn(), Price.of(25_000, "EUR"), NOW);

        PortfolioDto.PortfolioSummaryJson summary = mapper.map(portfolio, EUR);
        assertThat(summary.getProfitStatus()).isEqualTo(ProfitStatus.COMPUTED);
        assertThat(summary.getUnrealisedProfit())
                .as("worth 70 000, and now known to have cost 25 000")
                .isEqualTo(Money.of(45_000, "EUR"));
    }

    /**
     * The answer is recorded as the owner's, which is the one provenance a synchronisation may not
     * overwrite silently (D6). An answer the next exchange reading would erase is not worth asking
     * for — that protection is what makes the question legitimate.
     */
    @Test
    void shouldRecordItAsTheOwnersOwnAndProtectItFromTheNextReading() {
        Portfolio portfolio = onboarded();

        portfolio.stateCostOfPosition(
                Ticker.of("BTC"), SubName.transferredIn(), Price.of(25_000, "EUR"), NOW);

        CostBasis stated = portfolio.getAssets().getFirst().getCostBasis();
        assertThat(stated.provenance()).isEqualTo(Provenance.USER_PROVIDED);
        assertThat(CostReconciliation.mayReplace(
                Provenance.USER_PROVIDED, Provenance.EXCHANGE_REPORTED)).isFalse();
    }

    /** It prices what is held now; units arriving later are not covered by an older answer. */
    @Test
    void shouldCoverWhatIsHeldRatherThanWhateverArrivesLater() {
        Portfolio portfolio = onboarded();
        portfolio.stateCostOfPosition(
                Ticker.of("BTC"), SubName.transferredIn(), Price.of(25_000, "EUR"), NOW);

        portfolio.synchronisePosition(Ticker.of("BTC"), SubName.transferredIn(),
                Quantity.of(1), null, NOW);

        PortfolioDto.AssetSummaryJson row = row(portfolio);
        assertThat(row.getQuantity()).isEqualTo(Quantity.of(2));
        assertThat(row.getCoverage())
                .as("one of the two units is priced, and the row says so rather than stretching")
                .isEqualTo(0.5);
        assertThat(row.isAwaitingCost()).isTrue();
    }

    /** Naming a position that is not held is refused, rather than creating one out of an answer. */
    @Test
    void shouldRefuseToPriceAPositionThatIsNotHeld() {
        Portfolio portfolio = onboarded();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> portfolio.stateCostOfPosition(
                        Ticker.of("ETH"), SubName.transferredIn(), Price.of(2_000, "EUR"), NOW))
                .isInstanceOf(com.multi.vidulum.portfolio.domain.AssetNotFoundException.class);
    }
}
