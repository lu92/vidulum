package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommandHandler;
import com.multi.vidulum.exchange_connection.app.commands.reconnect.ReconnectExchangeCommandHandler;
import com.multi.vidulum.exchange_connection.app.commands.sync.RecordPortfolioSyncCommandHandler;
import com.multi.vidulum.exchange_connection.api.ConnectionStatus;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.api.error.IllegalConnectionTransitionException;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import com.multi.vidulum.portfolio.app.InMemoryPortfolioRepository;
import com.multi.vidulum.quotation.api.InMemoryQuotationApi;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecDto;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecRestController;
import com.multi.vidulum.portfolio_spec.app.commands.answer.AnswerPortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.confirm.ConfirmPortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.create.CreatePortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.app.commands.resume.ResumeConnectionCommandHandler;
import com.multi.vidulum.portfolio_spec.app.queries.GetPortfolioSpecQueryHandler;
import com.multi.vidulum.portfolio_spec.domain.AnswerKind;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coming back after a break (tasks H1 and H3).
 *
 * <p>Resuming used to be a status transition and nothing more: {@code REVOKED → ACTIVE}, portfolio
 * untouched. The connection then reported itself as working while the figures were from before the
 * break — and, worse, it <b>recorded a synchronisation</b>, because every transition overwrote the
 * one timestamp that was supposed to mean "the portfolio agrees with the exchange". So the one
 * field a reader would use to judge freshness said "just now" over week-old numbers.
 *
 * <p>Two changes, and they are a pair. H1 splits the timestamp in two, so coming back into service
 * no longer claims anything about the numbers. H3 then makes resuming actually read the account,
 * producing a specification against the portfolio that survived the break — which is what makes
 * {@code ACTIVE} mean something again.
 *
 * <p>What is deliberately still missing: the gap is <b>counted, not explained</b>. The difference
 * knows the balance changed, not whether something was bought or arrived from elsewhere, and those
 * two decide the cost basis and the contribution ledger. That is H5.
 */
class ResumeAfterBreakComponentTest {

    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");
    private static final Broker OKX = Broker.of("OKX");
    private static final String CONNECTION = "conn-1";

    /** Onboarded on day one, revoked on day thirty, back on day one hundred and twenty. */
    private static final ZonedDateTime ONBOARDED = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final ZonedDateTime BROKE_OFF = ZonedDateTime.parse("2022-01-30T00:00:00Z");
    private static final ZonedDateTime RETURNED = ZonedDateTime.parse("2022-05-01T00:00:00Z");

    private final Clock clock = Clock.fixed(ONBOARDED.toInstant(), ZoneOffset.UTC);
    private final InMemorySpecRepository specRepository = new InMemorySpecRepository();
    private final DomainPortfolioRepository portfolioRepository = new InMemoryPortfolioRepository(clock);
    private final InMemoryExchangeConnectionRepositoryForSpec connections =
            new InMemoryExchangeConnectionRepositoryForSpec();

    /** The real exchange handlers over that store — a connection genuinely transitions. */
    private final ExchangeConnectionApiForSpec exchangeApi =
            new ExchangeConnectionApiForSpec(connections, clock);
    private UserId caller = ALICE;
    private final AuthenticatedUserProvider authenticatedUserProvider = () -> caller;

    private final InMemoryQuotationApi quotationApiFake = InMemoryQuotationApi.strict()
            .publish("BTC/EUR", 50_000);
    private final CommandGateway commandGateway = commandGateway();

    private CommandGateway commandGateway() {
        CommandGateway gateway = new CommandGateway();
        CreatePortfolioSpecCommandHandler createSpec = new CreatePortfolioSpecCommandHandler(
                specRepository, portfolioRepository, exchangeApi, clock);
        ReconnectExchangeCommandHandler reconnect =
                new ReconnectExchangeCommandHandler(connections, clock);
        RecordPortfolioSyncCommandHandler recordSync =
                new RecordPortfolioSyncCommandHandler(connections);

        gateway.registerCommandHandler(createSpec);
        gateway.registerCommandHandler(reconnect);
        gateway.registerCommandHandler(recordSync);
        gateway.registerCommandHandler(new AnswerPortfolioSpecCommandHandler(specRepository));
        gateway.registerCommandHandler(new ConfirmPortfolioSpecCommandHandler(
                specRepository, portfolioRepository, new PortfolioFactory(),
                quotationApiFake, exchangeApi));
        gateway.registerCommandHandler(new ResumeConnectionCommandHandler(
                exchangeApi, createSpec));
        return gateway;
    }

    private QueryGateway queryGateway() {
        QueryGateway gateway = new QueryGateway();
        gateway.registerQueryHandler(new GetPortfolioSpecQueryHandler(specRepository));
        return gateway;
    }

    private final PortfolioSpecRestController controller = new PortfolioSpecRestController(
            commandGateway, queryGateway(), authenticatedUserProvider, clock);

    // --- setup ----------------------------------------------------------------------------------

    private static PortfolioSpecDto.SnapshotPositionJson btc(double total, double traded, Double price) {
        return new PortfolioSpecDto.SnapshotPositionJson(
                "BTC", Quantity.of(total), Quantity.of(traded), null,
                price == null ? null : Price.of(price, "USD"));
    }

    private void pendingConnection(UserId owner) {
        connections.save(ExchangeConnection.pending(
                ExchangeConnectionId.of(CONNECTION), owner, OKX, "349378528917283",
                ExchangeEnvironment.DEMO, "EEA", ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL, Currency.of("EUR"), ONBOARDED));
    }

    /** One account, onboarded and then cut off: 1.3 BTC held, 0.3 of it traded at 50 000. */
    private PortfolioId onboardThenBreakOff() {
        pendingConnection(ALICE);
        String specId = controller.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", CONNECTION, "EUR", null, ONBOARDED, List.of(btc(1.3, 0.3, 50_000.0)))).id();
        controller.answer(specId, new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", "transferred-in", Quantity.of(1.0), AnswerKind.COST_UNKNOWN, null))));
        PortfolioId portfolioId = PortfolioId.of(controller.confirm(specId,
                new PortfolioSpecDto.ConfirmSpecJson("My OKX", "EUR", "OKX", ONBOARDED,
                        List.of(btc(1.3, 0.3, 50_000.0)))).portfolioId());

        ExchangeConnection connection = connections.findById(ExchangeConnectionId.of(CONNECTION)).orElseThrow();
        connection.revoke("api key expired", BROKE_OFF);
        connections.save(connection);
        return portfolioId;
    }

    private PortfolioSpecDto.ResumeConnectionResultJson resume(
            PortfolioSpecDto.SnapshotPositionJson position) {
        return controller.resume(CONNECTION, new PortfolioSpecDto.ResumeConnectionJson(
                "OKX", RETURNED, List.of(position)));
    }

    private ExchangeConnection connection() {
        return connections.findById(ExchangeConnectionId.of(CONNECTION)).orElseThrow();
    }

    // --- H1: what the timestamps say ------------------------------------------------------------

    /**
     * The assertion the whole of H1 exists for. Onboarding synchronised on day one; the break
     * changed the status twice more; and the figures are still day one's.
     */
    @Test
    void shouldNotClaimASynchronisationJustBecauseTheConnectionCameBack() {
        onboardThenBreakOff();

        ExchangeConnection beforeResuming = connection();
        assertThat(beforeResuming.getStatus()).isEqualTo(ConnectionStatus.REVOKED);
        assertThat(beforeResuming.getStatusChangedAt()).isEqualTo(BROKE_OFF);
        assertThat(beforeResuming.getPortfolioSyncedAt())
                .as("the numbers have not moved since onboarding")
                .isEqualTo(ONBOARDED);
    }

    /** Resuming puts it back in service, and still does not pretend the numbers are fresh. */
    @Test
    void shouldResumeWithoutTheNumbersBecomingFresh() {
        onboardThenBreakOff();

        resume(btc(2.0, 0.3, 50_000.0));

        ExchangeConnection resumed = connection();
        assertThat(resumed.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(resumed.getStatusReason()).isNull();
        assertThat(resumed.getPortfolioSyncedAt())
                .as("a specification is waiting to be confirmed — nothing has been applied yet")
                .isEqualTo(ONBOARDED);
    }

    /** It is confirmation that moves the anchor, stamped with the snapshot it applied. */
    @Test
    void shouldMoveTheAnchorWhenTheSpecificationIsConfirmed() {
        onboardThenBreakOff();
        String specId = resume(btc(2.0, 0.3, 50_000.0)).specification().id();
        controller.answer(specId, new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", "transferred-in", Quantity.of(0.7), AnswerKind.COST_UNKNOWN, null))));

        controller.confirm(specId, new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", RETURNED, List.of(btc(2.0, 0.3, 50_000.0))));

        assertThat(connection().getPortfolioSyncedAt())
                .as("stamped with the moment the snapshot describes, not the instant of writing")
                .isEqualTo(RETURNED);
    }

    // --- H3: resuming is synchronisation, not onboarding ----------------------------------------

    /**
     * The difference is measured against the portfolio that survived the break, so the owner is
     * asked about the 0.7 that appeared — not about all 2.0 they hold.
     */
    @Test
    void shouldMeasureTheDifferenceAgainstThePortfolioThatSurvivedTheBreak() {
        PortfolioId portfolioId = onboardThenBreakOff();

        PortfolioSpecDto.ResumeConnectionResultJson resumed = resume(btc(2.0, 0.3, 50_000.0));

        assertThat(resumed.nothingToSynchronise()).isFalse();
        assertThat(resumed.specification().portfolioId())
                .as("the specification is still unapplied; this field is set on application")
                .isNull();
        assertThat(resumed.specification().differences())
                .singleElement()
                .satisfies(difference -> assertThat(difference.quantity())
                        .as("what appeared during the break, not the whole holding")
                        .isEqualTo(Quantity.of(0.7)));
        assertThat(portfolioRepository.findById(portfolioId)).isPresent();
    }

    /** Confirming updates that portfolio rather than creating a second one (the D13 guarantee). */
    @Test
    void shouldUpdateTheSurvivingPortfolioRatherThanCreateANewOne() {
        PortfolioId portfolioId = onboardThenBreakOff();
        String specId = resume(btc(2.0, 0.3, 50_000.0)).specification().id();
        controller.answer(specId, new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", "transferred-in", Quantity.of(0.7), AnswerKind.COST_UNKNOWN, null))));

        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId,
                new PortfolioSpecDto.ConfirmSpecJson("My OKX", "EUR", "OKX", RETURNED,
                        List.of(btc(2.0, 0.3, 50_000.0))));

        assertThat(applied.portfolioId()).isEqualTo(portfolioId.getId());
        assertThat(portfolioRepository.findById(portfolioId).orElseThrow().getAssets())
                .as("one position per ticker and sub-name, updated in place")
                .hasSize(2);
    }

    /**
     * Nothing moved during the break. No specification — an empty one is a question with no
     * content (D8) — but the synchronisation <b>is</b> recorded, because already being in
     * agreement is agreement. Leaving the anchor behind would send the future gap replay looking
     * through history for differences that do not exist.
     */
    @Test
    void shouldRecordTheSynchronisationWhenNothingChangedDuringTheBreak() {
        onboardThenBreakOff();

        PortfolioSpecDto.ResumeConnectionResultJson resumed = resume(btc(1.3, 0.3, 50_000.0));

        assertThat(resumed.nothingToSynchronise()).isTrue();
        assertThat(resumed.specification()).isNull();
        assertThat(connection().getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(connection().getPortfolioSyncedAt())
                .as("the account was read and found to agree — that is a synchronisation")
                .isEqualTo(RETURNED);
    }

    // --- refusals -------------------------------------------------------------------------------

    /** Nothing may be left behind by a connection that cannot be resumed. */
    @Test
    void shouldRefuseToResumeAConnectionThatWasNeverConfirmed() {
        pendingConnection(ALICE);

        assertThatThrownBy(() -> resume(btc(1.3, 0.3, 50_000.0)))
                .isInstanceOf(IllegalConnectionTransitionException.class);

        assertThat(specRepository.findByUserId(ALICE))
                .as("a refused resume must not leave a specification behind")
                .isEmpty();
    }

    /** Someone else's connection is not found, the rule A9 settled — not forbidden. */
    @Test
    void shouldNotFindSomebodyElsesConnection() {
        onboardThenBreakOff();
        caller = BOB;

        assertThatThrownBy(() -> resume(btc(2.0, 0.3, 50_000.0)))
                .isInstanceOf(RuntimeException.class);

        caller = ALICE;
        assertThat(connection().getStatus())
                .as("and it is left exactly as it was")
                .isEqualTo(ConnectionStatus.REVOKED);
    }
}
