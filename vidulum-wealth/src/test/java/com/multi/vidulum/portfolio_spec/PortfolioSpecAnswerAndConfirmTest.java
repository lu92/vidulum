package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.portfolio.domain.portfolio.Contribution;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionStatus;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommandHandler;
import com.multi.vidulum.exchange_connection.domain.ConnectionStatus;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.IllegalConnectionTransitionException;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import com.multi.vidulum.portfolio.app.InMemoryPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecDto;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecRestController;
import com.multi.vidulum.portfolio_spec.app.commands.answer.AnswerPortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.cancel.CancelPortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.confirm.ConfirmPortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.create.CreatePortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.queries.GetPortfolioSpecQueryHandler;
import com.multi.vidulum.portfolio_spec.domain.AnswerKind;
import com.multi.vidulum.portfolio_spec.domain.AnswerNotApplicableException;
import com.multi.vidulum.portfolio_spec.domain.IllegalSpecTransitionException;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecNotFoundException;
import com.multi.vidulum.portfolio_spec.domain.SnapshotChangedException;
import com.multi.vidulum.portfolio_spec.domain.SnapshotExpiredException;
import com.multi.vidulum.portfolio_spec.domain.UnansweredQuestionsException;
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
 * Answering a specification and applying it (tasks D2 and D3), driven through the controller and
 * real gateways — no Spring, no containers.
 *
 * <p>This is where the chain finally closes: before D3 existed,
 * {@code ExchangeConnection.confirm} had no production caller at all, so a connection could never
 * leave {@code PENDING} and {@code reconnect} could never succeed. The last test here is the one
 * that proves it now can.
 */
class PortfolioSpecAnswerAndConfirmTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final Broker OKX = Broker.of("OKX");
    private static final String CONNECTION = "conn-1";

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemorySpecRepository specRepository = new InMemorySpecRepository();
    private final DomainPortfolioRepository portfolioRepository = new InMemoryPortfolioRepository(clock);
    private final InMemoryExchangeConnectionRepositoryForSpec connections =
            new InMemoryExchangeConnectionRepositoryForSpec();

    private final ConfirmExchangeConnectionCommandHandler confirmConnectionHandler =
            new ConfirmExchangeConnectionCommandHandler(connections, clock);

    private UserId caller = ALICE;
    private final AuthenticatedUserProvider authenticatedUserProvider = () -> caller;

    /**
     * Prices for the opening contribution (C12). Confirmation now needs them: it records what the
     * account was worth on arrival, and that cannot wait until the first read.
     */
    private final QuoteRestClient quotes = new QuoteRestClient() {
        @Override
        public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
            return AssetPriceMetadata.builder()
                    .symbol(symbol)
                    .currentPrice(symbol.getOrigin().equals(symbol.getDestination())
                            ? Price.one(symbol.getDestination().getId())
                            : Price.of(50_000, symbol.getDestination().getId()))
                    .build();
        }

        @Override
        public AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker) {
            return AssetBasicInfo.notFound(ticker);
        }

        @Override
        public void registerBasicInfoAboutAsset(Broker broker, AssetBasicInfo assetBasicInfo) {
        }
    };

    private final CommandGateway commandGateway = commandGateway();
    private final QueryGateway queryGateway = queryGateway();

    private CommandGateway commandGateway() {
        CommandGateway gateway = new CommandGateway();
        gateway.registerCommandHandler(new CreatePortfolioSpecCommandHandler(
                specRepository, portfolioRepository, connections, clock));
        gateway.registerCommandHandler(new AnswerPortfolioSpecCommandHandler(specRepository));
        gateway.registerCommandHandler(new CancelPortfolioSpecCommandHandler(specRepository));
        gateway.registerCommandHandler(new ConfirmPortfolioSpecCommandHandler(
                specRepository, connections, portfolioRepository, new PortfolioFactory(),
                quotes, confirmConnectionHandler));
        return gateway;
    }

    private QueryGateway queryGateway() {
        QueryGateway gateway = new QueryGateway();
        gateway.registerQueryHandler(new GetPortfolioSpecQueryHandler(specRepository));
        return gateway;
    }

    private final PortfolioSpecRestController controller = new PortfolioSpecRestController(
            commandGateway, queryGateway, authenticatedUserProvider, clock);

    private static PortfolioSpecDto.SnapshotPositionJson btc(double total, double traded, Double price) {
        return new PortfolioSpecDto.SnapshotPositionJson(
                "BTC", Quantity.of(total), Quantity.of(traded), null,
                price == null ? null : Price.of(price, "USD"));
    }

    /** The same line, with part of it committed to an open order (task D5). */
    private static PortfolioSpecDto.SnapshotPositionJson btcFrozen(
            double total, double traded, double frozen) {
        return new PortfolioSpecDto.SnapshotPositionJson(
                "BTC", Quantity.of(total), Quantity.of(traded), Quantity.of(frozen),
                Price.of(50_000.0, "USD"));
    }

    private PortfolioSpecDto.ConfirmSpecJson confirmBodyFrozen(
            double total, double traded, double frozen) {
        return new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, List.of(btcFrozen(total, traded, frozen)));
    }

    private PortfolioSpecDto.PortfolioSpecJson createSpec() {
        return controller.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", CONNECTION, "EUR", null, NOW, List.of(btc(1.3, 0.3, 50_000.0))));
    }

    /** The same specification, anchored to a reading that is already older than the TTL. */
    private PortfolioSpecDto.PortfolioSpecJson createSpecTakenAt(ZonedDateTime takenAt) {
        return controller.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", CONNECTION, "EUR", null, takenAt, List.of(btc(1.3, 0.3, 50_000.0))));
    }

    private PortfolioSpecDto.PortfolioSpecJson answerUnknown(String specId) {
        return controller.answer(specId, new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", "transferred-in", Quantity.of(1.0), AnswerKind.COST_UNKNOWN, null))));
    }

    private PortfolioSpecDto.ConfirmSpecJson confirmBody(double total, double traded) {
        return new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, List.of(btc(total, traded, 50_000.0)));
    }

    /**
     * Creating a specification now checks the connection it names, so one has to exist before any
     * of these tests can get as far as the behaviour they are about.
     */
    @BeforeEach
    void seedConnection() {
        pendingConnection();
    }

    private ExchangeConnection pendingConnection() {
        ExchangeConnection connection = ExchangeConnection.pending(
                ExchangeConnectionId.of(CONNECTION), ALICE, OKX, "349378528917283",
                com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment.DEMO, "EEA",
                ReportedKeyPermissions.of("read_only"), CredentialsMode.EXTERNAL,
                Currency.of("EUR"), NOW);
        connections.save(connection);
        return connection;
    }

    @Test
    void shouldRecordACostTheUserSupplied() {
        String specId = createSpec().id();

        PortfolioSpecDto.PortfolioSpecJson answered = controller.answer(specId,
                new PortfolioSpecDto.AnswerSpecJson(List.of(
                        new PortfolioSpecDto.GivenAnswerJson("BTC", "transferred-in",
                                Quantity.of(1.0), AnswerKind.COST_PROVIDED, Price.of(30_000, "USD")))));

        PortfolioSpecDto.DifferenceJson transferredIn = answered.differences().stream()
                .filter(d -> d.subName().equals("transferred-in")).findFirst().orElseThrow();

        assertThat(answered.status()).isEqualTo("CONFIRMED");
        assertThat(transferredIn.costProvenance()).isEqualTo(Provenance.USER_PROVIDED.name());
        assertThat(transferredIn.costAvgPrice()).isEqualTo(Price.of(30_000, "USD"));
        assertThat(transferredIn.answerKind()).isEqualTo("COST_PROVIDED");
    }

    /**
     * "I do not know" is a decision, not a gap. Without it, a position nobody priced would be
     * indistinguishable from one nobody got round to.
     */
    @Test
    void shouldRecordAnExplicitlyUnknownCost() {
        String specId = createSpec().id();

        PortfolioSpecDto.PortfolioSpecJson answered = answerUnknown(specId);

        PortfolioSpecDto.DifferenceJson transferredIn = answered.differences().stream()
                .filter(d -> d.subName().equals("transferred-in")).findFirst().orElseThrow();

        assertThat(answered.status()).isEqualTo("CONFIRMED");
        assertThat(transferredIn.answerKind()).isEqualTo("COST_UNKNOWN");
        assertThat(transferredIn.costProvenance()).isNull();
    }

    @Test
    void shouldRefuseAnAnswerOfTheWrongKindForTheQuestion() {
        String specId = createSpec().id();

        assertThatThrownBy(() -> controller.answer(specId,
                new PortfolioSpecDto.AnswerSpecJson(List.of(
                        new PortfolioSpecDto.GivenAnswerJson("BTC", "transferred-in",
                                Quantity.of(1.0), AnswerKind.WITHDRAWAL, null)))))
                .isInstanceOf(AnswerNotApplicableException.class);
    }

    /** The snapshot is the anchor: an answer naming a batch that does not exist is refused. */
    @Test
    void shouldRefuseAnAnswerAnchoredToABatchThatDoesNotExist() {
        String specId = createSpec().id();

        assertThatThrownBy(() -> controller.answer(specId,
                new PortfolioSpecDto.AnswerSpecJson(List.of(
                        new PortfolioSpecDto.GivenAnswerJson("BTC", "transferred-in",
                                Quantity.of(42), AnswerKind.COST_UNKNOWN, null)))))
                .isInstanceOf(AnswerNotApplicableException.class)
                .hasMessageContaining("no open question");
    }

    @Test
    void shouldRefuseAnAnswerToSomethingTheRulesAlreadySettled() {
        String specId = createSpec().id();

        assertThatThrownBy(() -> controller.answer(specId,
                new PortfolioSpecDto.AnswerSpecJson(List.of(
                        new PortfolioSpecDto.GivenAnswerJson("BTC", "traded",
                                Quantity.of(0.3), AnswerKind.COST_PROVIDED, Price.of(1, "USD"))))))
                .isInstanceOf(AnswerNotApplicableException.class)
                .hasMessageContaining("not waiting for an answer");
    }

    @Test
    void shouldRefuseToConfirmWhileAnythingIsStillOpen() {
        pendingConnection();
        String specId = createSpec().id();

        assertThatThrownBy(() -> controller.confirm(specId, confirmBody(1.3, 0.3)))
                .isInstanceOf(UnansweredQuestionsException.class);
    }

    /**
     * Confirmation always compares against a fresh snapshot, whatever the specification's age.
     * If the exchange moved on, the specification is recomputed and goes back for review.
     */
    @Test
    void shouldRefuseToConfirmAgainstAWorldThatMovedOn() {
        pendingConnection();
        String specId = createSpec().id();
        answerUnknown(specId);

        assertThatThrownBy(() -> controller.confirm(specId, confirmBody(2.0, 0.3)))
                .isInstanceOf(SnapshotChangedException.class);

        assertThat(controller.get(specId).status())
                .as("the specification is recomputed, not left as it was")
                .isIn("AWAITING_ANSWER", "DRAFT");
    }

    @Test
    void shouldCreateAPortfolioCarryingBothPositions() {
        pendingConnection();
        String specId = createSpec().id();
        answerUnknown(specId);

        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId, confirmBody(1.3, 0.3));

        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.portfolioId()).isNotBlank();

        Portfolio portfolio = portfolioRepository
                .findById(PortfolioId.of(applied.portfolioId())).orElseThrow();
        assertThat(portfolio.getName()).isEqualTo("My OKX");
        assertThat(portfolio.getBroker()).isEqualTo(OKX);
        assertThat(portfolio.getAssets()).hasSize(2);

        Asset traded = position(portfolio, SubName.traded());
        assertThat(traded.getQuantity()).isEqualTo(Quantity.of(0.3));
        assertThat(traded.getCostBasis().provenance()).isEqualTo(Provenance.EXCHANGE_REPORTED);

        Asset transferredIn = position(portfolio, SubName.transferredIn());
        assertThat(transferredIn.hasKnownCost())
                .as("the user said they do not know, and that is what gets stored")
                .isFalse();
    }

    /**
     * What a portfolio built from a snapshot can say about what its owner put in (tasks C9, C12).
     *
     * <p>This path passes through neither deposit nor withdrawal, so the field the ledger replaces
     * answered {@code 0} here — beside six figures of holdings, which reads as profit out of thin
     * air. The ledger opens instead with one entry: what the account was worth on the day we first
     * read it, marked {@code OPENING_SNAPSHOT} so nobody mistakes it for a deposit somebody made.
     */
    @Test
    void shouldOpenTheLedgerWithWhatTheAccountWasWorthOnArrival() {
        pendingConnection();
        String specId = createSpec().id();
        answerUnknown(specId);

        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId, confirmBody(1.3, 0.3));

        Portfolio portfolio = portfolioRepository
                .findById(PortfolioId.of(applied.portfolioId())).orElseThrow();

        assertThat(portfolio.getContributions()).singleElement().satisfies(opening -> {
            assertThat(opening.provenance())
                    .as("not EXCHANGE_REPORTED: the exchange said what is held, not how it arrived")
                    .isEqualTo(Provenance.OPENING_SNAPSHOT);
            assertThat(opening.direction()).isEqualTo(Contribution.Direction.IN);
            assertThat(opening.valueAtArrival().getAmount().doubleValue())
                    .as("1.3 BTC at 50 000, both positions counted - the unpriced one included")
                    .isEqualTo(65_000);
            assertThat(opening.valueAtArrival().getCurrency()).isEqualTo("EUR");
            assertThat(opening.dateTime())
                    .as("the moment of confirmation, taken from the clock rather than invented inside")
                    .isEqualTo(NOW);
            assertThat(opening.id().getId()).isNotBlank();
        });
    }

    /** And the summary states it, rather than reporting a zero it never learned. */
    @Test
    void shouldReportTheOpeningContributionInTheSummary() {
        pendingConnection();
        String specId = createSpec().id();
        answerUnknown(specId);

        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId, confirmBody(1.3, 0.3));
        Portfolio portfolio = portfolioRepository
                .findById(PortfolioId.of(applied.portfolioId())).orElseThrow();

        PortfolioDto.PortfolioSummaryJson summary =
                new PortfolioSummaryMapper(quotes).map(portfolio, Currency.of("EUR"));

        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.COMPUTED);
        assertThat(summary.getNetContributions()).isEqualTo(Money.of(65_000, "EUR"));
        assertThat(summary.getContributionCoverage()).isEqualTo(1.0);
    }

    /**
     * What the exchange has frozen arrives as a lock, not as a question (task D5).
     *
     * <p>Every position used to be onboarded entirely free, so an account with an open order told
     * its owner they could move money the exchange would refuse to release.
     *
     * <p>The freeze is reported per currency while positions are split by origin (C2), and units
     * are fungible, so the parts are decided by a rule: traded first, the rest spills over. What
     * is exact is the total — and that is the number an owner acts on.
     */
    @Test
    void shouldLockWhatTheExchangeHasFrozen() {
        pendingConnection();
        String specId = createSpec().id();
        answerUnknown(specId);

        PortfolioSpecDto.PortfolioSpecJson applied =
                controller.confirm(specId, confirmBodyFrozen(1.3, 0.3, 0.5));

        Portfolio portfolio = portfolioRepository
                .findById(PortfolioId.of(applied.portfolioId())).orElseThrow();

        Asset traded = position(portfolio, SubName.traded());
        assertThat(traded.getLocked())
                .as("the traded part absorbs the freeze first, and 0.3 is all of it")
                .isEqualTo(Quantity.of(0.3));
        assertThat(traded.getFree()).isEqualTo(Quantity.zero("Number"));

        Asset transferredIn = position(portfolio, SubName.transferredIn());
        assertThat(transferredIn.getLocked())
                .as("0.2 spills over onto what the exchange never priced")
                .isEqualTo(Quantity.of(0.2));
        assertThat(transferredIn.getFree()).isEqualTo(Quantity.of(0.8));

        assertThat(traded.getLocked().plus(transferredIn.getLocked()))
                .as("however the parts fall, the total is what the exchange said")
                .isEqualTo(Quantity.of(0.5));
    }

    /**
     * These locks are the exchange's, not ours. A local {@code AssetLock} is held against one of
     * our orders and released by unlocking it; minting an order id for a freeze we do not own
     * would create a lock nothing could ever release.
     */
    @Test
    void shouldNotInventLocalLocksForAnExchangeFreeze() {
        pendingConnection();
        String specId = createSpec().id();
        answerUnknown(specId);

        PortfolioSpecDto.PortfolioSpecJson applied =
                controller.confirm(specId, confirmBodyFrozen(1.3, 0.3, 0.5));

        Portfolio portfolio = portfolioRepository
                .findById(PortfolioId.of(applied.portfolioId())).orElseThrow();

        assertThat(position(portfolio, SubName.traded()).getActiveLocks()).isEmpty();
        assertThat(position(portfolio, SubName.transferredIn()).getActiveLocks()).isEmpty();
    }

    /** Nothing frozen leaves everything free, which is what it meant before D5 existed. */
    @Test
    void shouldLeaveEverythingFreeWhenNothingIsFrozen() {
        pendingConnection();
        String specId = createSpec().id();
        answerUnknown(specId);

        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId, confirmBody(1.3, 0.3));

        Portfolio portfolio = portfolioRepository
                .findById(PortfolioId.of(applied.portfolioId())).orElseThrow();

        assertThat(position(portfolio, SubName.traded()).getFree()).isEqualTo(Quantity.of(0.3));
        assertThat(position(portfolio, SubName.transferredIn()).getFree()).isEqualTo(Quantity.of(1.0));
    }

    /**
     * A refused answer decides something too (task D10): the specification is stale, and that has
     * to outlive the request. Missed by the aggregate's own tests — they saw the status change on
     * the object and had no repository to lose it in. A live run found it.
     */
    @Test
    void shouldRememberThatTheAnchorAgedOutAfterRefusingAnAnswer() {
        pendingConnection();
        String specId = createSpecTakenAt(NOW.minusMinutes(20)).id();

        assertThatThrownBy(() -> answerUnknown(specId))
                .isInstanceOf(SnapshotExpiredException.class);

        assertThat(controller.get(specId).status())
                .as("read back from the repository, not from the object the refusal touched")
                .isEqualTo("STALE");
    }

    /**
     * Walking away is an outcome (task D10). Without it the specification stays in
     * {@code AWAITING_ANSWER} forever and nothing can tell a decision still being made from one
     * nobody will ever make.
     */
    @Test
    void shouldLetTheOwnerAbandonASynchronisation() {
        pendingConnection();
        String specId = createSpec().id();

        PortfolioSpecDto.PortfolioSpecJson cancelled = controller.cancel(specId);

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> controller.confirm(specId, confirmBody(1.3, 0.3)))
                .isInstanceOf(IllegalSpecTransitionException.class);
    }

    /** Someone else's specification cannot be abandoned on their behalf — it answers 404. */
    @Test
    void shouldNotLetAStrangerCancelASynchronisation() {
        pendingConnection();
        String specId = createSpec().id();
        caller = UserId.of("U10000002");

        assertThatThrownBy(() -> controller.cancel(specId))
                .isInstanceOf(PortfolioSpecNotFoundException.class);
    }

    /**
     * The point of the whole chain: until this ran, {@code ExchangeConnection.confirm} had no
     * production caller, so no connection could reach {@code ACTIVE} and {@code reconnect} could
     * never succeed.
     */
    @Test
    void shouldPutTheConnectionIntoServiceWhenTheSpecificationIsApplied() {
        pendingConnection();
        String specId = createSpec().id();
        answerUnknown(specId);

        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId, confirmBody(1.3, 0.3));

        ExchangeConnection connection = connections
                .findById(ExchangeConnectionId.of(CONNECTION)).orElseThrow();
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(connection.getPortfolioId()).isEqualTo(PortfolioId.of(applied.portfolioId()));
    }

    @Test
    void shouldApplyASpecificationThatHasNoConnection() {
        PortfolioSpecDto.PortfolioSpecJson spec = controller.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", null, "EUR", null, NOW, List.of(btc(1.3, 0.3, 50_000.0))));
        answerUnknown(spec.id());

        assertThat(controller.confirm(spec.id(), confirmBody(1.3, 0.3)).status())
                .isEqualTo("APPLIED");
    }

    /**
     * There is no transaction spanning the portfolio and the connection, so a connection that
     * cannot be confirmed has to stop the operation before anything is written.
     *
     * <p>The connection here is already {@code ACTIVE} — onboarded once before — which is the
     * case that survives the check {@code create} now performs. A connection that simply does not
     * exist no longer reaches this point at all: the specification naming it is refused outright.
     */
    @Test
    void shouldNotCreateAPortfolioWhenTheConnectionCannotBeConfirmed() {
        String specId = createSpec().id();
        answerUnknown(specId);

        ExchangeConnection alreadyOnboarded = connections
                .findById(ExchangeConnectionId.of(CONNECTION)).orElseThrow();
        alreadyOnboarded.confirm(PortfolioId.of("PF-earlier"), NOW);
        connections.save(alreadyOnboarded);

        assertThatThrownBy(() -> controller.confirm(specId, confirmBody(1.3, 0.3)))
                .isInstanceOf(IllegalConnectionTransitionException.class);

        assertThat(portfolioRepository.findByUserId(ALICE))
                .as("nothing may be left behind by a failed confirmation")
                .isEmpty();
        assertThat(controller.get(specId).status()).isEqualTo("CONFIRMED");
    }

    private static Asset position(Portfolio portfolio, SubName subName) {
        return portfolio.getAssets().stream()
                .filter(asset -> asset.getSubName().equals(subName))
                .findFirst()
                .orElseThrow();
    }
}
