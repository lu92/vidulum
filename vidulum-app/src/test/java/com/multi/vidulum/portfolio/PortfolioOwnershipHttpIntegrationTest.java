package com.multi.vidulum.portfolio;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.awaitility.Awaitility;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two people, two portfolios, and the question nothing used to ask: whose is this? (task G2)
 *
 * <p>Every other test in this module is one user to whom everything belongs, which is exactly how
 * the hole survived. Demonstrated against a running backend before it was closed: with his own
 * token, a second user deposited 999 999 USD into a stranger's portfolio, read it in full, and
 * recorded a trade in it — three calls, all answering {@code 200}. The trade even carried his user
 * id inside her portfolio, so the ledger and the portfolio disagreed about whose it was.
 *
 * <p><b>At integration level on purpose.</b> The defect is that identity was read from the request
 * instead of the token, and only a real token can show that it now comes from the right place — a
 * component test supplies the identity itself and would prove the rule while assuming its source.
 *
 * <p>Two portfolios rather than one for the other half of the proof: not merely that a stranger is
 * refused, but that each portfolio ends holding exactly what its own owner put there.
 */
@Slf4j
@DisplayName("Portfolio ownership over HTTP")
class PortfolioOwnershipHttpIntegrationTest extends AuthenticatedHttpIntegrationTest {

    private PortfolioHttpActor alice;
    private PortfolioHttpActor bob;
    private String alicesPortfolio;
    private String bobsPortfolio;

    @BeforeEach
    void twoUsersEachWithAPortfolio() {
        alice = register("alice");
        bob = register("bob");

        // Gold has to have a price before a portfolio holding it can be valued, and placing an
        // order reads the portfolio to check the balance. Quotes are global, so publishing once
        // serves both users - they are not what this test is about.
        publishQuote("XAU", "USD", 1800);

        alicesPortfolio = createPortfolio(alice, "Alice's metals");
        bobsPortfolio = createPortfolio(bob, "Bob's metals");

        assertThat(alicesPortfolio).isNotEqualTo(bobsPortfolio);
    }

    private void publishQuote(String origin, String destination, double amount) {
        restTemplate.exchange(
                "http://localhost:" + port + "/quote/publish?broker=PM&origin=" + origin
                        + "&destination=" + destination + "&amount=" + amount
                        + "&currency=" + destination + "&pctChange=0",
                HttpMethod.GET, new HttpEntity<>(authenticatedHeaders()), Void.class);

        // Kafka carries the publication, so the cache fills a moment later.
        Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() ->
                restTemplate.exchange(
                        "http://localhost:" + port + "/quote/PM/" + origin + "/" + destination,
                        HttpMethod.GET, new HttpEntity<>(authenticatedHeaders()), String.class)
                        .getStatusCode() == HttpStatus.OK);
    }

    /**
     * A hand-entered trade travels over Kafka, so the portfolio catches up a moment after the
     * request returns. Waiting for the position is part of using this API, not a test artefact —
     * the component test never sees it because its bus is synchronous.
     */
    private void awaitPosition(PortfolioHttpActor actor, String portfolioId, String ticker, double quantity) {
        Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> {
            PortfolioDto.PortfolioSummaryJson portfolio = actor.getPortfolio(portfolioId, "USD").getBody();
            return portfolio != null && quantityOf(portfolio, ticker) == quantity;
        });
    }

    private PortfolioHttpActor register(String who) {
        String username = who + "_" + uniqueUsername();
        registerAndAuthenticate(username, username + "@test.com", "SecurePassword123!");
        return new PortfolioHttpActor(restTemplate, port, accessToken, userId);
    }

    private String createPortfolio(PortfolioHttpActor actor, String name) {
        ResponseEntity<PortfolioDto.PortfolioSummaryJson> response =
                actor.createPortfolio(name, "PM", "USD");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PortfolioDto.PortfolioSummaryJson portfolio = response.getBody();
        assertThat(portfolio).isNotNull();
        assertThat(portfolio.getUserId())
                .as("a portfolio belongs to whoever's token created it, never to a body field")
                .isEqualTo(actor.userId());
        return portfolio.getPortfolioId();
    }

    // --- the diagonal: everyone on their own ---------------------------------------------------

    @Test
    @DisplayName("each owner may fund, order and trade in their own portfolio")
    void shouldLetEachOwnerActOnTheirOwnPortfolio() {
        assertThat(alice.deposit(alicesPortfolio, Money.of(20_000, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(bob.deposit(bobsPortfolio, Money.of(5_000, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(alice.tradeByHand(alicesPortfolio, "XAU/USD", Side.BUY,
                Quantity.of(2, "oz"), Price.of(1800, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        awaitPosition(alice, alicesPortfolio, "XAU", 2.0);

        assertThat(alice.placeOrder(alicesPortfolio, "XAU/USD", Side.SELL,
                Price.of(2000, "USD"), Quantity.of(1, "oz")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(bob.placeOrder(bobsPortfolio, "XAU/USD", Side.BUY,
                Price.of(1800, "USD"), Quantity.of(1, "oz")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // --- the off-diagonal: nobody on anyone else's --------------------------------------------

    @Test
    @DisplayName("a stranger's portfolio is not found, rather than forbidden")
    void shouldHideAPortfolioFromEveryoneButItsOwner() {
        assertThat(bob.getPortfolioExpectingError(alicesPortfolio, "USD").getStatusCode())
                .as("404 and not 403 - a refusal that confirms the id exists is itself a leak")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(alice.getPortfolioExpectingError(bobsPortfolio, "USD").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("nobody may pay money into a portfolio that is not theirs")
    void shouldRefuseADepositIntoSomeoneElsesPortfolio() {
        assertThat(bob.depositExpectingError(alicesPortfolio, Money.of(999_999, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(alice.depositExpectingError(bobsPortfolio, Money.of(999_999, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("nobody may place an order against a portfolio that is not theirs")
    void shouldRefuseAnOrderAgainstSomeoneElsesPortfolio() {
        alice.deposit(alicesPortfolio, Money.of(20_000, "USD"));
        bob.deposit(bobsPortfolio, Money.of(20_000, "USD"));

        assertThat(bob.placeOrderExpectingError(alicesPortfolio, "XAU/USD", Side.BUY,
                Price.of(1800, "USD"), Quantity.of(1, "oz")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(alice.placeOrderExpectingError(bobsPortfolio, "XAU/USD", Side.BUY,
                Price.of(1800, "USD"), Quantity.of(1, "oz")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("nobody may record a trade in a portfolio that is not theirs")
    void shouldRefuseATradeInSomeoneElsesPortfolio() {
        alice.deposit(alicesPortfolio, Money.of(20_000, "USD"));
        bob.deposit(bobsPortfolio, Money.of(20_000, "USD"));

        assertThat(bob.tradeByHandExpectingError(alicesPortfolio, "XAU/USD", Side.BUY,
                Quantity.of(1, "oz"), Price.of(1800, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(alice.tradeByHandExpectingError(bobsPortfolio, "XAU/USD", Side.BUY,
                Quantity.of(1, "oz"), Price.of(1800, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- and the consequence ------------------------------------------------------------------

    /**
     * The half a refusal alone does not prove: after both have worked and both have been turned
     * away, each portfolio holds what its own owner put there and nothing from the other.
     */
    @Test
    @DisplayName("each portfolio ends holding only what its own owner put there")
    void shouldKeepTwoPortfoliosApart() {
        alice.deposit(alicesPortfolio, Money.of(20_000, "USD"));
        bob.deposit(bobsPortfolio, Money.of(5_000, "USD"));

        alice.tradeByHand(alicesPortfolio, "XAU/USD", Side.BUY, Quantity.of(2, "oz"), Price.of(1800, "USD"));
        awaitPosition(alice, alicesPortfolio, "XAU", 2.0);

        bob.tradeByHandExpectingError(alicesPortfolio, "XAU/USD", Side.BUY,
                Quantity.of(5, "oz"), Price.of(1800, "USD"));
        alice.depositExpectingError(bobsPortfolio, Money.of(999_999, "USD"));

        PortfolioDto.PortfolioSummaryJson hers = alice.getPortfolio(alicesPortfolio, "USD").getBody();
        PortfolioDto.PortfolioSummaryJson his = bob.getPortfolio(bobsPortfolio, "USD").getBody();

        assertThat(hers).isNotNull();
        assertThat(his).isNotNull();
        assertThat(hers.getUserId()).isEqualTo(alice.userId());
        assertThat(his.getUserId()).isEqualTo(bob.userId());

        assertThat(cash(hers))
                .as("20 000 in, 3 600 spent on gold - Bob's refused purchase spent nothing")
                .isEqualTo(16_400.0);
        assertThat(ounces(hers)).isEqualTo(2.0);

        assertThat(cash(his))
                .as("untouched by Alice's refused deposit")
                .isEqualTo(5_000.0);
        assertThat(his.getAssets())
                .as("Bob bought nothing, so he holds nothing but cash")
                .noneMatch(asset -> asset.getTicker().equals("XAU"));
    }

    @Test
    @DisplayName("the aggregated view shows the caller's own portfolios and no others")
    void shouldAggregateOnlyTheCallersOwnHoldings() {
        alice.deposit(alicesPortfolio, Money.of(20_000, "USD"));
        bob.deposit(bobsPortfolio, Money.of(5_000, "USD"));

        PortfolioDto.AggregatedPortfolioSummaryJson hers =
                alice.getAggregatedPortfolio("USD").getBody();

        assertThat(hers).isNotNull();
        assertThat(hers.getUserId()).isEqualTo(alice.userId());
        assertThat(hers.getPortfolioIds())
                .containsExactly(alicesPortfolio)
                .doesNotContain(bobsPortfolio);
        assertThat(hers.getCurrentValue().getAmount().doubleValue()).isEqualTo(20_000.0);
    }

    private static double cash(PortfolioDto.PortfolioSummaryJson portfolio) {
        return quantityOf(portfolio, "USD");
    }

    private static double ounces(PortfolioDto.PortfolioSummaryJson portfolio) {
        return quantityOf(portfolio, "XAU");
    }

    private static double quantityOf(PortfolioDto.PortfolioSummaryJson portfolio, String ticker) {
        return portfolio.getAssets().stream()
                .filter(asset -> asset.getTicker().equals(ticker))
                .mapToDouble(asset -> asset.getQuantity().getQty())
                .sum();
    }
}
