package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Contribution;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionStatus;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * What a portfolio can honestly say about what its owner put in (task C9).
 *
 * <p>The field this replaces answered {@code 0} for a portfolio built from an exchange snapshot,
 * because only {@code deposit} and {@code withdraw} ever moved it. Zero beside six figures of
 * holdings is the one answer worse than none: an interface renders it as a number, and "you put in
 * nothing and have 147 000" reads as profit from thin air.
 *
 * <p>A ledger answers it instead, and the rule that governs it is C4's: a total added up from a
 * minority of the entries does not describe the ledger, so it is withheld rather than annotated.
 */
class ContributionLedgerComponentTest {

    private static final Broker BROKER = Broker.of("PM");
    private static final UserId OWNER = UserId.of("U10000001");
    private static final Currency USD = Currency.of("USD");
    private static final ZonedDateTime WHEN = ZonedDateTime.parse("2022-01-01T00:00:00Z");

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
                throw new AssertionError("no quote published for " + symbol.getId());
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

    private Portfolio emptyPortfolio() {
        return new PortfolioFactory().empty(PortfolioId.generate(), "Ledger", OWNER, BROKER, USD);
    }

    private PortfolioDto.PortfolioSummaryJson summaryOf(Portfolio portfolio) {
        return mapper.map(portfolio, USD);
    }

    // --- the ledger, through the one mechanism -----------------------------------------------

    @Test
    void shouldSayNothingRatherThanZeroWhenNobodyHasPutAnythingIn() {
        PortfolioDto.PortfolioSummaryJson summary = summaryOf(emptyPortfolio());

        assertThat(summary.getNetContributions())
                .as("the field this replaces answered 0 here, and an interface shows 0 as a fact")
                .isNull();
        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.NOTHING_CONTRIBUTED);
        assertThat(summary.getContributionCoverage()).isNull();
    }

    @Test
    void shouldRecordEveryDepositAndWithdrawalInOrder() {
        Portfolio portfolio = emptyPortfolio();
        portfolio.depositMoney(Money.of(10_000, "USD"), "first", WHEN);
        portfolio.depositMoney(Money.of(5_000, "USD"), "second", WHEN.plusDays(1));
        portfolio.withdrawMoney(Money.of(2_000, "USD"), "third", WHEN.plusDays(2));

        assertThat(portfolio.getContributions()).extracting(Contribution::id)
                .as("one mechanism: deposits and withdrawals write the same ledger")
                .containsExactly("first", "second", "third");

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);
        assertThat(summary.getNetContributions())
                .as("10 000 + 5 000 - 2 000, net of what was taken back out")
                .isEqualTo(Money.of(13_000, "USD"));
        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.COMPUTED);
        assertThat(summary.getContributionCoverage()).isEqualTo(1.0);
    }

    /**
     * Taking everything back out leaves a ledger, not an empty one. "I hold nothing" and "I put
     * nothing in" are different sentences, and the field this replaces could only say the second.
     */
    @Test
    void shouldRememberMovementsAfterTheMoneyIsGoneAgain() {
        Portfolio portfolio = emptyPortfolio();
        portfolio.depositMoney(Money.of(10_000, "USD"), "in", WHEN);
        portfolio.withdrawMoney(Money.of(10_000, "USD"), "out", WHEN.plusDays(1));

        assertThat(portfolio.getContributions()).hasSize(2);
        assertThat(summaryOf(portfolio).getNetContributions()).isEqualTo(Money.of(0, "USD"));
        assertThat(summaryOf(portfolio).getContributionStatus()).isEqualTo(ContributionStatus.COMPUTED);
    }

    /** Identity and time come from the caller, so a fixed clock actually fixes them. */
    @Test
    void shouldTakeItsMomentAndIdentityFromTheCaller() {
        Portfolio portfolio = emptyPortfolio();
        portfolio.depositMoney(Money.of(1_000, "USD"), "pinned", WHEN);

        assertThat(portfolio.getContributions()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("pinned");
            assertThat(entry.when()).isEqualTo(WHEN);
            assertThat(entry.provenance()).isEqualTo(Provenance.ASSUMED_PAR);
        });
    }

    // --- the rule C4 established, applied one level down --------------------------------------

    /**
     * A figure added up from a minority of the ledger is withheld, exactly as a profit is.
     *
     * <p>Nothing in production writes a valueless contribution yet — backfill (C13) will be the
     * first, when it records "one bitcoin arrived on this date" without a price for that date. The
     * rule is here so that the number does not quietly start lying the day it does.
     */
    @Test
    void shouldWithholdATotalAddedUpFromAMinorityOfTheLedger() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("USD")
                .contributed(Money.of(1_000, "USD"))
                .contributedOfUnknownValue(Money.of(1, "BTC"))
                .contributedOfUnknownValue(Money.of(2, "ETH"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.WITHHELD_LOW_COVERAGE);
        assertThat(summary.getNetContributions()).isNull();
        assertThat(summary.getContributionCoverage())
                .as("one entry of three carries a value")
                .isCloseTo(0.333, within(0.001));
    }

    @Test
    void shouldSayWhenNothingInTheLedgerCarriesAValue() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("USD")
                .contributedOfUnknownValue(Money.of(1, "BTC"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.NO_KNOWN_VALUE);
        assertThat(summary.getNetContributions()).isNull();
        assertThat(summary.getContributionCoverage()).isEqualTo(0.0);
    }

    /**
     * A contribution is recorded in the currency it arrived in; the summary may be asked for in
     * another. Skipping the conversion made a 10 000 USD deposit answer "10 000 EUR".
     */
    @Test
    void shouldRestateTheLedgerInWhicheverCurrencyIsAskedFor() {
        prices.put("USD/EUR", 0.95);
        Portfolio portfolio = emptyPortfolio();
        portfolio.depositMoney(Money.of(10_000, "USD"), "in", WHEN);

        PortfolioDto.PortfolioSummaryJson inEuro = mapper.map(portfolio, Currency.of("EUR"));

        assertThat(inEuro.getNetContributions()).isEqualTo(Money.of(9_500, "EUR"));
    }

    /** A value without a source, or a source without a value, is a half-written fact. */
    @Test
    void shouldRefuseAHalfWrittenContribution() {
        assertThatThrownBy(() -> new Contribution("a", WHEN, Contribution.Direction.IN,
                Money.of(1, "USD"), Money.of(1, "USD"), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new Contribution("b", WHEN, Contribution.Direction.IN,
                Money.of(1, "USD"), null, Provenance.ASSUMED_PAR))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
