package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionDto;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionRestController;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommandHandler;
import com.multi.vidulum.exchange_connection.app.commands.connect.ConnectExchangeCommandHandler;
import com.multi.vidulum.exchange_connection.app.commands.reconnect.ReconnectExchangeCommandHandler;
import com.multi.vidulum.exchange_connection.app.commands.revoke.RevokeExchangeConnectionCommandHandler;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionQueryHandler;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionsOfUserQueryHandler;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapter;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapters;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionNotFoundException;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.domain.UnknownExchangeRegionException;
import com.multi.vidulum.portfolio.app.InMemoryPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecDto;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecRestController;
import com.multi.vidulum.portfolio_spec.app.commands.answer.AnswerPortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.confirm.ConfirmPortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.create.CreatePortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.queries.GetPortfolioSpecQueryHandler;
import com.multi.vidulum.portfolio_spec.domain.AnswerKind;
import com.multi.vidulum.portfolio_spec.domain.ConnectionMismatchException;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole onboarding path, across both controllers, at component speed.
 *
 * <p>Everything else tests one half: {@code ExchangeConnectionControllerComponentTest} stops at a
 * {@code PENDING} connection, and {@code PortfolioSpecAnswerAndConfirmTest} starts from one built
 * by hand. Neither covers the seam — that the {@code connectionId} one controller hands out is
 * the one the other accepts, that the caller is the same user on both sides, and that the
 * portfolio ends up with the broker and currency the <b>connection</b> decided.
 *
 * <p>It also walks every state an {@code ExchangeConnection} can reach:
 * {@code PENDING → ACTIVE → REVOKED → ACTIVE}.
 */
class OnboardingFlowComponentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");
    private static final Broker OKX = Broker.of("OKX");
    private static final String ACCOUNT_UID = "349378528917283";

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final InMemoryExchangeConnectionRepositoryForSpec connections =
            new InMemoryExchangeConnectionRepositoryForSpec();
    private final InMemorySpecRepository specs = new InMemorySpecRepository();
    private final DomainPortfolioRepository portfolios = new InMemoryPortfolioRepository(clock);

    /** Stands in for the OKX module, which wealth's tests must not depend on. */
    private final ExchangeAdapters adapters = new ExchangeAdapters(List.of(new ExchangeAdapter() {
        @Override
        public Broker broker() {
            return OKX;
        }

        @Override
        public void validateRegion(String region) {
            if (!"EEA".equals(region == null ? null : region.trim().toUpperCase(Locale.ROOT))) {
                throw new UnknownExchangeRegionException(OKX, region, List.of("EEA"));
            }
        }
    }));

    private UserId caller = ALICE;
    private final AuthenticatedUserProvider authenticatedUserProvider = () -> caller;

    private final ConfirmExchangeConnectionCommandHandler confirmConnectionHandler =
            new ConfirmExchangeConnectionCommandHandler(connections, clock);

    private final CommandGateway commandGateway = commandGateway();
    private final QueryGateway queryGateway = queryGateway();

    private CommandGateway commandGateway() {
        CommandGateway gateway = new CommandGateway();
        gateway.registerCommandHandler(new ConnectExchangeCommandHandler(connections, adapters, clock));
        gateway.registerCommandHandler(new ReconnectExchangeCommandHandler(connections, clock));
        gateway.registerCommandHandler(new RevokeExchangeConnectionCommandHandler(connections, clock));
        gateway.registerCommandHandler(new CreatePortfolioSpecCommandHandler(specs, portfolios, connections, clock));
        gateway.registerCommandHandler(new AnswerPortfolioSpecCommandHandler(specs));
        gateway.registerCommandHandler(new ConfirmPortfolioSpecCommandHandler(
                specs, connections, portfolios, new PortfolioFactory(), confirmConnectionHandler, clock));
        return gateway;
    }

    private QueryGateway queryGateway() {
        QueryGateway gateway = new QueryGateway();
        gateway.registerQueryHandler(new GetExchangeConnectionQueryHandler(connections));
        gateway.registerQueryHandler(new GetExchangeConnectionsOfUserQueryHandler(connections, adapters));
        gateway.registerQueryHandler(new GetPortfolioSpecQueryHandler(specs));
        return gateway;
    }

    private final ExchangeConnectionRestController connectionController =
            new ExchangeConnectionRestController(commandGateway, queryGateway, authenticatedUserProvider);

    private final PortfolioSpecRestController specController =
            new PortfolioSpecRestController(commandGateway, queryGateway, authenticatedUserProvider, clock);

    // --- steps of the flow, in the order a caller performs them -------------------------------

    private ExchangeConnectionDto.ExchangeConnectionJson connect() {
        return connectionController.connect(new ExchangeConnectionDto.ConnectExchangeJson(
                "OKX", ACCOUNT_UID, ExchangeEnvironment.LIVE, "EEA", "read_only", "EUR", null));
    }

    private PortfolioSpecDto.PortfolioSpecJson createSpec(String connectionId) {
        return specController.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", connectionId, "EUR", null, NOW, List.of(btc(1.3, 0.3))));
    }

    private void answerUnknownCost(String specId) {
        specController.answer(specId, new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", "transferred-in", Quantity.of(1.0), AnswerKind.COST_UNKNOWN, null))));
    }

    private PortfolioSpecDto.PortfolioSpecJson confirm(String specId, String currency, String broker) {
        return specController.confirm(specId, new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", currency, broker, NOW, List.of(btc(1.3, 0.3))));
    }

    private static PortfolioSpecDto.SnapshotPositionJson btc(double total, double traded) {
        return new PortfolioSpecDto.SnapshotPositionJson(
                "BTC", Quantity.of(total), Quantity.of(traded), Price.of(50_000, "EUR"));
    }

    private static Asset position(Portfolio portfolio, SubName subName) {
        return portfolio.getAssets().stream()
                .filter(asset -> asset.getSubName().equals(subName))
                .findFirst()
                .orElseThrow();
    }

    // --- the flow ------------------------------------------------------------------------------

    /**
     * Connect, compute the difference, answer what only a human can answer, apply. The
     * identifiers flow between the two controllers untouched.
     */
    @Test
    void shouldCarryAUserFromConnectingAnAccountToAValuedPortfolio() {
        ExchangeConnectionDto.ExchangeConnectionJson connection = connect();
        assertThat(connection.status()).isEqualTo("PENDING");
        assertThat(connection.portfolioId()).isNull();

        PortfolioSpecDto.PortfolioSpecJson spec = createSpec(connection.id());
        assertThat(spec.connectionId())
                .as("the id one controller hands out is the one the other stores")
                .isEqualTo(connection.id());
        assertThat(spec.status()).isEqualTo("AWAITING_ANSWER");

        answerUnknownCost(spec.id());
        assertThat(specController.get(spec.id()).status()).isEqualTo("CONFIRMED");

        PortfolioSpecDto.PortfolioSpecJson applied = confirm(spec.id(), "EUR", "OKX");
        assertThat(applied.status()).isEqualTo("APPLIED");

        Portfolio portfolio = portfolios.findById(PortfolioId.of(applied.portfolioId())).orElseThrow();
        assertThat(portfolio.getUserId()).isEqualTo(ALICE);
        assertThat(portfolio.getAssets()).hasSize(2);
        assertThat(position(portfolio, SubName.traded()).getCostBasis().provenance())
                .isEqualTo(Provenance.EXCHANGE_REPORTED);
        assertThat(position(portfolio, SubName.transferredIn()).hasKnownCost()).isFalse();

        ExchangeConnectionDto.ExchangeConnectionJson afterwards =
                connectionController.get(connection.id());
        assertThat(afterwards.status()).isEqualTo("ACTIVE");
        assertThat(afterwards.portfolioId()).isEqualTo(applied.portfolioId());
    }

    /**
     * Every state the connection can reach, in one run. Until {@code revoke} existed,
     * {@code REVOKED} had no producer, so this walk stopped at {@code ACTIVE} and the reconnect
     * endpoint — correct and tested — could never actually be used.
     */
    @Test
    void shouldWalkTheWholeConnectionLifecycle() {
        String connectionId = connect().id();
        String specId = createSpec(connectionId).id();
        answerUnknownCost(specId);
        String portfolioId = confirm(specId, "EUR", "OKX").portfolioId();

        assertThat(connectionController.get(connectionId).status()).isEqualTo("ACTIVE");

        ExchangeConnectionDto.ExchangeConnectionJson revoked = connectionController.revoke(
                connectionId, new ExchangeConnectionDto.RevokeConnectionJson("api key expired"));

        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.statusReason()).isEqualTo("api key expired");
        assertThat(revoked.portfolioId())
                .as("losing the exchange is an interruption - the portfolio stays")
                .isEqualTo(portfolioId);

        ExchangeConnectionDto.ExchangeConnectionJson reconnected =
                connectionController.reconnect(connectionId);

        assertThat(reconnected.status()).isEqualTo("ACTIVE");
        assertThat(reconnected.statusReason()).isNull();
        assertThat(reconnected.portfolioId()).isEqualTo(portfolioId);
        assertThat(connections.findByUserId(ALICE))
                .as("coming back is a lookup, not a second connection")
                .hasSize(1);
    }

    /**
     * The valuation currency belongs to the connection: quotes are published against it and must
     * be cached before the portfolio exists. A confirmation that states another one has the wrong
     * picture and is told so, rather than silently getting a portfolio nothing can price.
     */
    @Test
    void shouldRefuseAConfirmationThatContradictsTheConnectionsCurrency() {
        String connectionId = connect().id();
        String specId = createSpec(connectionId).id();
        answerUnknownCost(specId);

        assertThatThrownBy(() -> confirm(specId, "USD", "OKX"))
                .isInstanceOf(ConnectionMismatchException.class)
                .hasMessageContaining("denominationCurrency")
                .hasMessageContaining("USD")
                .hasMessageContaining("EUR");

        assertThat(portfolios.findByUserId(ALICE)).isEmpty();
        assertThat(connectionController.get(connectionId).status()).isEqualTo("PENDING");
    }

    /**
     * The broker decides whose quote cache serves the portfolio, so an OKX connection must not
     * end up feeding a portfolio filed under another exchange.
     */
    @Test
    void shouldRefuseAConfirmationThatContradictsTheConnectionsBroker() {
        String connectionId = connect().id();
        String specId = createSpec(connectionId).id();
        answerUnknownCost(specId);

        assertThatThrownBy(() -> confirm(specId, "EUR", "BINANCE"))
                .isInstanceOf(ConnectionMismatchException.class)
                .hasMessageContaining("broker");

        assertThat(portfolios.findByUserId(ALICE)).isEmpty();
    }

    @Test
    void shouldTakeBrokerAndCurrencyFromTheConnectionRatherThanTheRequest() {
        String connectionId = connect().id();
        String specId = createSpec(connectionId).id();
        answerUnknownCost(specId);

        String portfolioId = confirm(specId, "eur", "okx").portfolioId();

        Portfolio portfolio = portfolios.findById(PortfolioId.of(portfolioId)).orElseThrow();
        assertThat(portfolio.getBroker())
                .as("normalised on the connection, not echoed from the request")
                .isEqualTo(OKX);
        assertThat(portfolio.getAllowedDepositCurrency()).isEqualTo(Currency.of("EUR"));
    }

    /** One user's connection cannot be used to build another user's portfolio. */
    @Test
    void shouldNotLetAnotherUserConfirmAgainstSomeoneElsesConnection() {
        String connectionId = connect().id();
        String specId = createSpec(connectionId).id();
        answerUnknownCost(specId);

        caller = BOB;

        assertThatThrownBy(() -> confirm(specId, "EUR", "OKX"))
                .hasMessageContaining(specId);
        assertThat(portfolios.findByUserId(BOB)).isEmpty();
    }

    /**
     * Reconnecting after a break produces a specification whose known state is the portfolio from
     * before — that is what stops a synchronisation from looking like onboarding all over again.
     */
    @Test
    void shouldComputeTheNextSpecificationAgainstThePortfolioFromBeforeTheBreak() {
        String connectionId = connect().id();
        String specId = createSpec(connectionId).id();
        answerUnknownCost(specId);
        String portfolioId = confirm(specId, "EUR", "OKX").portfolioId();

        connectionController.revoke(connectionId,
                new ExchangeConnectionDto.RevokeConnectionJson("api key expired"));
        connectionController.reconnect(connectionId);

        PortfolioSpecDto.PortfolioSpecJson next = specController.create(
                new PortfolioSpecDto.CreateSpecJson(
                        "OKX", connectionId, "EUR", portfolioId, NOW, List.of(btc(1.8, 0.8))));

        assertThat(next.differences())
                .as("only what moved, not the whole portfolio again")
                .hasSize(1);
        assertThat(next.status())
                .as("a purchase the exchange priced needs no human")
                .isEqualTo("DRAFT");
    }

    /**
     * The snapshot's cash and a later deposit are the same position — task C10, found on a live
     * run rather than by any test here.
     *
     * <p>Two halves had each been right on their own. {@code DifferenceEngine} split every
     * snapshot line into {@code traded} and {@code transferred-in}; {@code deposit} looked under
     * {@code none}, where C2 puts cash. Neither side was checked against the other, so a portfolio
     * onboarded with 4 386 EUR answered a 100 EUR deposit with 200 OK and a second euro position
     * — and a withdrawal afterwards could only see the smaller of the two.
     *
     * <p>This is the seam, so the test walks it end to end rather than asserting on the engine:
     * the assertion that matters is that the money the exchange reported and the money the user
     * puts in afterwards land on one balance.
     */
    @Test
    void shouldLetALaterDepositLandOnTheCashTheSnapshotBroughtIn() {
        List<PortfolioSpecDto.SnapshotPositionJson> reported = List.of(btc(1.3, 0.3), eur(5_000));

        String connectionId = connect().id();
        PortfolioSpecDto.PortfolioSpecJson spec = specController.create(
                new PortfolioSpecDto.CreateSpecJson("OKX", connectionId, "EUR", null, NOW, reported));

        assertThat(spec.differences())
                .as("cash is never a question - one euro costs one euro when euro is the unit")
                .filteredOn(difference -> difference.ticker().equals("EUR"))
                .singleElement()
                .satisfies(difference -> {
                    assertThat(difference.subName()).isEqualTo(SubName.none().getName());
                    assertThat(difference.question()).isNull();
                    assertThat(difference.costProvenance()).isEqualTo(Provenance.ASSUMED_PAR.name());
                });

        answerUnknownCost(spec.id());

        String portfolioId = specController.confirm(spec.id(), new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, reported)).portfolioId();

        Portfolio portfolio = portfolios.findById(PortfolioId.of(portfolioId)).orElseThrow();
        assertThat(portfolio.findAssetsByTicker(Ticker.of("EUR")))
                .as("cash arrives as one position, not split by how it got there")
                .hasSize(1);
        assertThat(position(portfolio, SubName.none()).getQuantity()).isEqualTo(Quantity.of(5_000));

        portfolio.depositMoney(Money.of(100, "EUR"));

        assertThat(portfolio.findAssetsByTicker(Ticker.of("EUR")))
                .as("the deposit finds the existing balance instead of opening a parallel one")
                .hasSize(1);
        assertThat(position(portfolio, SubName.none()).getQuantity()).isEqualTo(Quantity.of(5_100));
    }

    /**
     * The confirmation may not rename the currency the specification was computed with: that
     * choice decided which line was filed as cash, so a portfolio valued in anything else would
     * key its cash differently from the decisions that produced it.
     */
    @Test
    void shouldRefuseToConfirmInADifferentCurrencyThanTheSpecificationUsed() {
        String connectionId = connect().id();
        String specId = createSpec(connectionId).id();
        answerUnknownCost(specId);

        assertThatThrownBy(() -> confirm(specId, "USD", "OKX"))
                .isInstanceOf(ConnectionMismatchException.class);
    }

    private static PortfolioSpecDto.SnapshotPositionJson eur(double total) {
        return new PortfolioSpecDto.SnapshotPositionJson(
                "EUR", Quantity.of(total), Quantity.zero(), null);
    }

    /**
     * The disagreement is caught on the first request, not on the last.
     *
     * <p>Both {@code create} and {@code confirm} compare the currency against the connection, and
     * only the earlier check is any use: the currency decides which snapshot line is filed as cash
     * (C10), so a specification built with the wrong one asks the wrong questions. Worse, by
     * confirmation time no value is accepted any more — one check rejects what contradicts the
     * connection, the other what contradicts the specification — so the caller would be left with
     * a specification they had answered in full and could never apply.
     */
    @Test
    void shouldRefuseASpecificationInADifferentCurrencyThanTheConnection() {
        String connectionId = connect().id();

        assertThatThrownBy(() -> specController.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", connectionId, "USD", null, NOW, List.of(btc(1.3, 0.3)))))
                .isInstanceOf(ConnectionMismatchException.class)
                .hasMessageContaining("EUR");

        assertThat(specs.findByUserId(ALICE))
                .as("nothing is stored for a request that was refused")
                .isEmpty();
    }

    /** Someone else's connection is not found, rather than forbidden — the rule A9 established. */
    @Test
    void shouldNotLetACallerBuildASpecificationOnAnotherUsersConnection() {
        String alices = connect().id();

        caller = BOB;

        assertThatThrownBy(() -> specController.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", alices, "EUR", null, NOW, List.of(btc(1.3, 0.3)))))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
    }
}
