package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommandHandler;
import com.multi.vidulum.exchange_connection.app.commands.sync.RecordPortfolioSyncCommandHandler;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import com.multi.vidulum.portfolio.app.InMemoryPortfolioRepository;
import com.multi.vidulum.quotation.api.AssetBasicInfo;
import com.multi.vidulum.quotation.api.InMemoryQuotationApi;
import com.multi.vidulum.portfolio.domain.portfolio.Contribution;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionStatus;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecDto;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecRestController;
import com.multi.vidulum.portfolio_spec.app.commands.answer.AnswerPortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.confirm.ConfirmPortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.create.CreatePortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.queries.GetPortfolioSpecQueryHandler;
import com.multi.vidulum.portfolio_spec.domain.AnswerKind;
import com.multi.vidulum.quotation.api.QuoteNotFoundException;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Onboarding an account that holds something nobody quotes (task C18).
 *
 * <p>Found on a live account, not by a test, and the interesting part is that the code contradicted
 * its own documentation. {@code openingContribution} promised "valued position by position… so a
 * single unpriced holding costs one line instead of the whole number" and then summed through
 * {@code fetch}, which throws. So {@code POST /portfolio-spec/{id}/confirm} answered
 * {@code 404 QUOTE_NOT_FOUND} and the owner could not open a portfolio at all — five of their six
 * positions priced, and the sixth a token the OKX demo market does not quote in euro.
 *
 * <p>The same class of defect as C16, one call further on: C16 made the portfolio <b>read</b>
 * tolerate an unpriceable position and deliberately left {@code confirm} alone.
 */
class UnpricedHoldingAtOnboardingComponentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final Broker OKX = Broker.of("OKX");
    private static final Currency EUR = Currency.of("EUR");
    private static final String CONNECTION = "conn-1";

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemorySpecRepository specRepository = new InMemorySpecRepository();
    private final DomainPortfolioRepository portfolioRepository = new InMemoryPortfolioRepository(clock);
    private final InMemoryExchangeConnectionRepositoryForSpec connections =
            new InMemoryExchangeConnectionRepositoryForSpec();

    /** Only what the test publishes has a price; everything else refuses, as the live one does. */

    private final InMemoryQuotationApi quotes = InMemoryQuotationApi.asInProduction();
    private final CommandGateway commandGateway = commandGateway();

    private CommandGateway commandGateway() {
        CommandGateway gateway = new CommandGateway();
        gateway.registerCommandHandler(new CreatePortfolioSpecCommandHandler(
                specRepository, portfolioRepository, connections, clock));
        gateway.registerCommandHandler(new AnswerPortfolioSpecCommandHandler(specRepository));
        gateway.registerCommandHandler(new ConfirmPortfolioSpecCommandHandler(
                specRepository, connections, portfolioRepository, new PortfolioFactory(),
                quotes, new ConfirmExchangeConnectionCommandHandler(connections, clock),
                new RecordPortfolioSyncCommandHandler(connections)));
        return gateway;
    }

    private QueryGateway queryGateway() {
        QueryGateway gateway = new QueryGateway();
        gateway.registerQueryHandler(new GetPortfolioSpecQueryHandler(specRepository));
        return gateway;
    }

    private final AuthenticatedUserProvider authenticatedUserProvider = () -> ALICE;
    private final PortfolioSpecRestController controller = new PortfolioSpecRestController(
            commandGateway, queryGateway(), authenticatedUserProvider, clock);

    // --- setup ----------------------------------------------------------------------------------

    private static PortfolioSpecDto.SnapshotPositionJson position(
            String ticker, double total, double traded, Double reportedPrice) {
        return new PortfolioSpecDto.SnapshotPositionJson(
                ticker, Quantity.of(total), Quantity.of(traded), Quantity.of(0),
                reportedPrice == null ? null : Price.of(reportedPrice, "USD"));
    }

    private void pendingConnection() {
        connections.save(ExchangeConnection.pending(
                ExchangeConnectionId.of(CONNECTION), ALICE, OKX, "349378528917283",
                ExchangeEnvironment.DEMO, "EEA", ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL, EUR, NOW));
    }

    /**
     * The live shape, shrunk: bitcoin the exchange prices, and a stablecoin it does not quote
     * against the euro at all.
     */
    private Portfolio onboardWithAnUnquotedHolding() {
        quotes.publish("BTC/EUR", 50_000d);
        quotes.publish("USD/EUR", 0.9);
        pendingConnection();

        List<PortfolioSpecDto.SnapshotPositionJson> snapshot = List.of(
                position("BTC", 1.0, 1.0, 50_000d),
                position("USDC", 400, 400, 1d));

        String specId = controller.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", CONNECTION, "EUR", null, NOW, snapshot)).id();
        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId,
                new PortfolioSpecDto.ConfirmSpecJson("My OKX", "EUR", "OKX", NOW, snapshot));

        return portfolioRepository.findById(PortfolioId.of(applied.portfolioId())).orElseThrow();
    }

    // --- the defect -----------------------------------------------------------------------------

    /** The assertion the task exists for: the portfolio gets written at all. */
    @Test
    void shouldOnboardAnAccountHoldingSomethingNobodyQuotes() {
        Portfolio portfolio = onboardWithAnUnquotedHolding();

        assertThat(portfolio.getAssets())
                .as("both holdings are recorded; only one of them has a value")
                .hasSize(2);
    }

    /** The unquoted holding becomes an entry with no value, not a missing entry. */
    @Test
    void shouldRecordTheUnquotedHoldingAsAContributionOfUnknownValue() {
        Portfolio portfolio = onboardWithAnUnquotedHolding();

        assertThat(portfolio.getContributions()).hasSize(2);

        Contribution priced = portfolio.getContributions().stream()
                .filter(Contribution::hasKnownValue).findFirst().orElseThrow();
        assertThat(priced.valueAtArrival()).isEqualTo(
                com.multi.vidulum.common.Money.of(50_000, "EUR"));
        assertThat(priced.provenance()).isEqualTo(Provenance.OPENING_SNAPSHOT);

        Contribution unpriced = portfolio.getContributions().stream()
                .filter(entry -> !entry.hasKnownValue()).findFirst().orElseThrow();
        assertThat(unpriced.what())
                .as("what arrived is known — four hundred of them — only its worth is not")
                .isEqualTo(com.multi.vidulum.common.Money.of(400, "USDC"));
        assertThat(unpriced.provenance())
                .as("provenance describes where the value came from, and there is no value")
                .isNull();
        assertThat(unpriced.dateTime()).isEqualTo(NOW);
        assertThat(unpriced.id().getId()).isNotBlank();
    }

    /**
     * And the ledger says how much of itself it speaks for. One of two entries is valued, which is
     * exactly the threshold {@code ProfitCoverage.MEANINGFUL_FROM} admits — so the total stands and
     * the share states what it covers, instead of the figure passing for the whole.
     */
    @Test
    void shouldLetTheLedgerSayHowMuchOfItselfItSpeaksFor() {
        Portfolio portfolio = onboardWithAnUnquotedHolding();

        PortfolioDto.PortfolioSummaryJson summary =
                new PortfolioSummaryMapper(quotes).map(portfolio, EUR);

        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.COMPUTED);
        assertThat(summary.getNetContributions())
                .as("the bitcoin alone; the stablecoin contributes nothing to the sum")
                .isEqualTo(com.multi.vidulum.common.Money.of(50_000, "EUR"));
        assertThat(summary.getContributionCoverage())
                .as("one of two entries is valued, and the payload says exactly that")
                .isEqualTo(0.5);
    }

    /**
     * Two of three unquoted and the total goes, because a figure built from a third of the ledger
     * does not describe the ledger — C4's rule, reached here through entries rather than value.
     */
    @Test
    void shouldWithholdTheTotalWhenMostOfTheLedgerHasNoValue() {
        quotes.publish("BTC/EUR", 50_000d);
        quotes.publish("USD/EUR", 0.9);
        pendingConnection();

        List<PortfolioSpecDto.SnapshotPositionJson> snapshot = List.of(
                position("BTC", 1.0, 1.0, 50_000d),
                position("USDC", 400, 400, 1d),
                position("SOL", 12, 12, 100d));
        String specId = controller.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", CONNECTION, "EUR", null, NOW, snapshot)).id();
        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId,
                new PortfolioSpecDto.ConfirmSpecJson("My OKX", "EUR", "OKX", NOW, snapshot));

        Portfolio portfolio = portfolioRepository
                .findById(PortfolioId.of(applied.portfolioId())).orElseThrow();
        PortfolioDto.PortfolioSummaryJson summary =
                new PortfolioSummaryMapper(quotes).map(portfolio, EUR);

        assertThat(summary.getContributionStatus())
                .isEqualTo(ContributionStatus.WITHHELD_LOW_COVERAGE);
        assertThat(summary.getNetContributions()).isNull();
        assertThat(summary.getContributionCoverage()).isCloseTo(
                1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    /** Nothing changes for an account every part of which can be priced. */
    @Test
    void shouldStillSumTheWholeLedgerWhenEverythingIsPriced() {
        quotes.publish("BTC/EUR", 50_000d);
        quotes.publish("USDC/EUR", 0.9);
        quotes.publish("USD/EUR", 0.9);
        pendingConnection();

        List<PortfolioSpecDto.SnapshotPositionJson> snapshot = List.of(
                position("BTC", 1.0, 1.0, 50_000d),
                position("USDC", 400, 400, 1d));
        String specId = controller.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", CONNECTION, "EUR", null, NOW, snapshot)).id();
        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId,
                new PortfolioSpecDto.ConfirmSpecJson("My OKX", "EUR", "OKX", NOW, snapshot));

        Portfolio portfolio = portfolioRepository
                .findById(PortfolioId.of(applied.portfolioId())).orElseThrow();
        PortfolioDto.PortfolioSummaryJson summary =
                new PortfolioSummaryMapper(quotes).map(portfolio, EUR);

        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.COMPUTED);
        assertThat(summary.getNetContributions())
                .as("50 000 for the bitcoin and 360 for the four hundred stablecoins")
                .isEqualTo(com.multi.vidulum.common.Money.of(50_360, "EUR"));
        assertThat(summary.getContributionCoverage()).isEqualTo(1.0);
    }
}
