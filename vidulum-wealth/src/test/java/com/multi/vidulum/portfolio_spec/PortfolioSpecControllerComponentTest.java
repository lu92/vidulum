package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import com.multi.vidulum.portfolio.app.InMemoryPortfolioRepository;
import com.multi.vidulum.portfolio.app.PortfolioFixture;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecDto;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecRestController;
import com.multi.vidulum.portfolio_spec.app.commands.create.CreatePortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.queries.GetPortfolioSpecQueryHandler;
import com.multi.vidulum.portfolio_spec.domain.NothingToSynchroniseException;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecNotFoundException;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The controller driven through real gateways, with in-memory repositories — no Spring, no
 * containers. Follows {@code ExchangeConnectionControllerComponentTest}: registering the handlers
 * the way {@code VidulumApplication} does means the reflection-based routing is exercised here
 * too, not only in a containerised test.
 */
class PortfolioSpecControllerComponentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemorySpecRepository specRepository = new InMemorySpecRepository();
    private final DomainPortfolioRepository portfolioRepository = new InMemoryPortfolioRepository(clock);
    private final InMemoryExchangeConnectionRepositoryForSpec connections =
            new InMemoryExchangeConnectionRepositoryForSpec();

    /**
     * The specification is now refused if it names a connection that says something else, so every
     * caller in this test needs one of their own to name.
     */
    @BeforeEach
    void seedConnections() {
        connections.save(pending("conn-1", ALICE));
        connections.save(pending("conn-2", BOB));
    }

    private static ExchangeConnection pending(String id, UserId owner) {
        return ExchangeConnection.pending(
                ExchangeConnectionId.of(id), owner, Broker.of("OKX"), "349378528917283",
                ExchangeEnvironment.DEMO, "EEA", ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL, Currency.of("EUR"), NOW);
    }

    private UserId caller = ALICE;
    private final AuthenticatedUserProvider authenticatedUserProvider = () -> caller;

    private final CommandGateway commandGateway = commandGateway();
    private final QueryGateway queryGateway = queryGateway();

    private CommandGateway commandGateway() {
        CommandGateway gateway = new CommandGateway();
        gateway.registerCommandHandler(new CreatePortfolioSpecCommandHandler(
                specRepository, portfolioRepository, connections, clock));
        return gateway;
    }

    private QueryGateway queryGateway() {
        QueryGateway gateway = new QueryGateway();
        gateway.registerQueryHandler(new GetPortfolioSpecQueryHandler(specRepository));
        return gateway;
    }

    private final PortfolioSpecRestController controller = new PortfolioSpecRestController(
            commandGateway, queryGateway, authenticatedUserProvider, clock);

    private static PortfolioSpecDto.CreateSpecJson request(String portfolioId, double total, double traded, Double price) {
        return request("conn-1", portfolioId, total, traded, price);
    }

    private static PortfolioSpecDto.CreateSpecJson request(
            String connectionId, String portfolioId, double total, double traded, Double price) {
        return new PortfolioSpecDto.CreateSpecJson(
                "OKX", connectionId, "EUR", portfolioId, NOW,
                List.of(new PortfolioSpecDto.SnapshotPositionJson(
                        "BTC", Quantity.of(total), Quantity.of(traded),
                        price == null ? null : Price.of(price, "USD"))));
    }

    private static PortfolioSpecDto.DifferenceJson of(
            PortfolioSpecDto.PortfolioSpecJson spec, String subName) {
        return spec.differences().stream()
                .filter(difference -> difference.subName().equals(subName))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldReturnTheWholeSpecificationAfterComputingIt() {
        PortfolioSpecDto.PortfolioSpecJson created = controller.create(request(null, 1.3, 0.3, 50_000.0));

        assertThat(created.id()).isNotBlank();
        assertThat(created.userId()).isEqualTo(ALICE.getId());
        assertThat(created.connectionId()).isEqualTo("conn-1");
        assertThat(created.status()).isEqualTo("AWAITING_ANSWER");
        assertThat(created.portfolioId()).isNull();
        assertThat(created.snapshotTakenAt()).isEqualTo(NOW);
        assertThat(created.snapshotExpired()).isFalse();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.differences()).hasSize(2);
    }

    @Test
    void shouldExposeSettledAndUnsettledDifferencesDistinctly() {
        PortfolioSpecDto.PortfolioSpecJson created = controller.create(request(null, 1.3, 0.3, 50_000.0));

        PortfolioSpecDto.DifferenceJson traded = of(created, "traded");
        assertThat(traded.question()).isNull();
        assertThat(traded.costProvenance()).isEqualTo(Provenance.EXCHANGE_REPORTED.name());
        assertThat(traded.costAvgPrice()).isEqualTo(Price.of(50_000, "USD"));

        PortfolioSpecDto.DifferenceJson transferredIn = of(created, "transferred-in");
        assertThat(transferredIn.question()).isEqualTo("ACQUISITION_COST");
        assertThat(transferredIn.costProvenance()).isNull();
        assertThat(transferredIn.costAvgPrice()).isNull();
    }

    @Test
    void shouldReadBackTheSpecificationItJustCreated() {
        PortfolioSpecDto.PortfolioSpecJson created = controller.create(request(null, 1.3, 0.3, 50_000.0));

        assertThat(controller.get(created.id()))
                .usingRecursiveComparison()
                .isEqualTo(created);
    }

    /**
     * The known state comes from a portfolio that already exists, which is what makes the second
     * synchronisation cheap instead of a repeat of onboarding.
     */
    @Test
    void shouldUseAnExistingPortfolioAsTheKnownState() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .id("portfolio-1")
                .with("BTC", Quantity.of(0.3),
                        CostBasis.of(Quantity.of(0.3), Price.of(50_000, "USD"), Provenance.EXCHANGE_REPORTED))
                .build();
        portfolioRepository.save(portfolio);

        PortfolioSpecDto.PortfolioSpecJson created =
                controller.create(request("portfolio-1", 0.8, 0.8, 55_000.0));

        assertThat(created.differences()).hasSize(1);
        assertThat(created.status())
                .as("a purchase the exchange priced must not require an answer")
                .isEqualTo("DRAFT");
        assertThat(of(created, "traded").quantity().getQty())
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void shouldRefuseToCreateASpecificationWhenNothingChanged() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .id("portfolio-1")
                .with("BTC", Quantity.of(0.3),
                        CostBasis.of(Quantity.of(0.3), Price.of(50_000, "USD"), Provenance.EXCHANGE_REPORTED))
                .build();
        portfolioRepository.save(portfolio);

        assertThatThrownBy(() -> controller.create(request("portfolio-1", 0.3, 0.3, 50_000.0)))
                .isInstanceOf(NothingToSynchroniseException.class);
    }

    /** An unknown portfolio means nothing is known, not that the request is invalid. */
    @Test
    void shouldTreatAnUnknownPortfolioAsAnEmptyKnownState() {
        PortfolioSpecDto.PortfolioSpecJson created =
                controller.create(request("no-such-portfolio", 1.3, 0.3, 50_000.0));

        assertThat(created.differences()).hasSize(2);
    }

    @Test
    void shouldAttributeTheSpecificationToTheAuthenticatedCaller() {
        caller = BOB;

        assertThat(controller.create(request("conn-2", null, 1.3, 0.3, 50_000.0)).userId())
                .isEqualTo(BOB.getId());
    }

    @Test
    void shouldHideAnotherCallersSpecification() {
        PortfolioSpecDto.PortfolioSpecJson alices = controller.create(request(null, 1.3, 0.3, 50_000.0));

        caller = BOB;

        assertThatThrownBy(() -> controller.get(alices.id()))
                .isInstanceOf(PortfolioSpecNotFoundException.class);
    }

    @Test
    void shouldReportAnUnknownSpecificationAsNotFound() {
        assertThatThrownBy(() -> controller.get("no-such-spec"))
                .isInstanceOf(PortfolioSpecNotFoundException.class);
    }

    @Test
    void shouldSurviveTheRepositoryRoundTrip() {
        PortfolioSpecDto.PortfolioSpecJson created = controller.create(request(null, 1.3, 0.3, 50_000.0));

        assertThat(specRepository.roundTripped(created.id()))
                .usingRecursiveComparison()
                .isEqualTo(specRepository.stored(created.id()));
    }
}
