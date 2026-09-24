package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommandHandler;
import com.multi.vidulum.exchange_connection.domain.ConnectionStatus;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import com.multi.vidulum.portfolio.app.InMemoryPortfolioRepository;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
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
import com.multi.vidulum.portfolio_spec.domain.NothingToSynchroniseException;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Reading the same exchange account a second time (task D13).
 *
 * <p>The specification did not remember which portfolio it had measured against — the field that
 * looked like it, {@code portfolioId}, is set on <b>application</b> and answers a different
 * question. So confirming a second synchronisation built a <b>second portfolio</b> holding only
 * the differences: the owner ended up with one portfolio showing last week's state and another
 * showing this week's change, and neither describing the account.
 *
 * <p>What saved us in practice was unrelated — a connection already in service is refused (D12) —
 * so a portfolio with no connection had nothing guarding it at all.
 */
class SecondSynchronisationComponentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final Broker OKX = Broker.of("OKX");
    private static final String CONNECTION = "conn-1";

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemorySpecRepository specRepository = new InMemorySpecRepository();
    private final DomainPortfolioRepository portfolioRepository = new InMemoryPortfolioRepository(clock);
    private final InMemoryExchangeConnectionRepositoryForSpec connections =
            new InMemoryExchangeConnectionRepositoryForSpec();
    private final AuthenticatedUserProvider authenticatedUserProvider = () -> ALICE;

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
        gateway.registerCommandHandler(new ConfirmPortfolioSpecCommandHandler(
                specRepository, connections, portfolioRepository, new PortfolioFactory(),
                quotes, new ConfirmExchangeConnectionCommandHandler(connections, clock)));
        return gateway;
    }

    private QueryGateway queryGateway() {
        QueryGateway gateway = new QueryGateway();
        gateway.registerQueryHandler(new GetPortfolioSpecQueryHandler(specRepository));
        return gateway;
    }

    private final PortfolioSpecRestController controller = new PortfolioSpecRestController(
            commandGateway, queryGateway, authenticatedUserProvider, clock);

    // --- setup: one account, onboarded once ----------------------------------------------------

    private static PortfolioSpecDto.SnapshotPositionJson btc(
            double total, double traded, Double price, Double frozen) {
        return new PortfolioSpecDto.SnapshotPositionJson(
                "BTC", Quantity.of(total), Quantity.of(traded),
                frozen == null ? null : Quantity.of(frozen),
                price == null ? null : Price.of(price, "USD"));
    }

    private void pendingConnection() {
        connections.save(ExchangeConnection.pending(
                ExchangeConnectionId.of(CONNECTION), ALICE, OKX, "349378528917283",
                ExchangeEnvironment.DEMO, "EEA", ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL, Currency.of("EUR"), NOW));
    }

    /** The first reading: 0.3 traded at 50 000, 1.0 arrived from elsewhere at a price nobody knows. */
    private PortfolioId onboard() {
        pendingConnection();
        String specId = controller.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", CONNECTION, "EUR", null, NOW, List.of(btc(1.3, 0.3, 50_000.0, null)))).id();
        controller.answer(specId, new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", "transferred-in", Quantity.of(1.0), AnswerKind.COST_UNKNOWN, null))));
        return PortfolioId.of(controller.confirm(specId, new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, List.of(btc(1.3, 0.3, 50_000.0, null)))).portfolioId());
    }

    /** A later reading of the same account, measured against the portfolio it produced. */
    private String synchronise(PortfolioId portfolioId,
                               PortfolioSpecDto.SnapshotPositionJson position) {
        return controller.create(new PortfolioSpecDto.CreateSpecJson(
                "OKX", CONNECTION, "EUR", portfolioId.getId(), NOW, List.of(position))).id();
    }

    private void answer(String specId, String subName, double quantity, AnswerKind kind) {
        controller.answer(specId, new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", subName, Quantity.of(quantity), kind, null))));
    }

    private Portfolio reload(PortfolioId portfolioId) {
        return portfolioRepository.findById(portfolioId).orElseThrow();
    }

    private static Asset position(Portfolio portfolio, SubName subName) {
        return portfolio.getAssets().stream()
                .filter(asset -> asset.getSubName().equals(subName))
                .findFirst().orElseThrow();
    }

    // --- the defect ----------------------------------------------------------------------------

    @Test
    void shouldUpdateThePortfolioItMeasuredAgainstRatherThanBuildingASecondOne() {
        PortfolioId portfolioId = onboard();

        // The account traded another 0.2 BTC since the first reading.
        String specId = synchronise(portfolioId, btc(1.5, 0.5, 60_000.0, null));
        PortfolioSpecDto.PortfolioSpecJson applied = controller.confirm(specId,
                new PortfolioSpecDto.ConfirmSpecJson(
                        "My OKX", "EUR", "OKX", NOW, List.of(btc(1.5, 0.5, 60_000.0, null))));

        assertThat(applied.portfolioId())
                .as("the same portfolio, not a new one holding only the difference")
                .isEqualTo(portfolioId.getId());
        assertThat(portfolioRepository.findByUserId(ALICE))
                .as("one account, one portfolio")
                .hasSize(1);

        Portfolio portfolio = reload(portfolioId);
        assertThat(position(portfolio, SubName.traded()).getQuantity()).isEqualTo(Quantity.of(0.5));
        assertThat(position(portfolio, SubName.transferredIn()).getQuantity()).isEqualTo(Quantity.of(1.0));
    }

    /**
     * The new units bring their own cost, and the position keeps a weighted average over both
     * parts — 0.3 at 50 000 and 0.2 at 60 000 (task D6).
     */
    @Test
    void shouldAbsorbTheCostOfWhatWasAdded() {
        PortfolioId portfolioId = onboard();

        String specId = synchronise(portfolioId, btc(1.5, 0.5, 60_000.0, null));
        controller.confirm(specId, new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, List.of(btc(1.5, 0.5, 60_000.0, null))));

        Asset traded = position(reload(portfolioId), SubName.traded());
        assertThat(traded.getCostBasis().quantity()).isEqualTo(Quantity.of(0.5));
        assertThat(traded.getCostBasis().avgPrice().getAmount().doubleValue())
                .as("(0.3 x 50 000 + 0.2 x 60 000) / 0.5")
                .isEqualTo(54_000);
    }

    /**
     * The E10 case, end to end: the same holding read back with no reported price at all — which
     * is what a coin sitting in the OKX Funding account looks like. The cost must survive.
     */
    @Test
    void shouldNotForgetACostWhenTheNewReadingIsSilentAboutIt() {
        PortfolioId portfolioId = onboard();

        String specId = synchronise(portfolioId, btc(1.5, 0.5, null, null));
        answer(specId, "traded", 0.2, AnswerKind.COST_UNKNOWN);
        controller.confirm(specId, new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, List.of(btc(1.5, 0.5, null, null))));

        Asset traded = position(reload(portfolioId), SubName.traded());
        assertThat(traded.hasKnownCost())
                .as("silence is not a statement that the cost is unknown")
                .isTrue();
        assertThat(traded.getCostBasis().avgPrice().getAmount().doubleValue()).isEqualTo(50_000);
        assertThat(traded.getCostBasis().quantity())
                .as("only the 0.3 we were told about is covered; the position grew past it")
                .isEqualTo(Quantity.of(0.3));
        assertThat(traded.getQuantity()).isEqualTo(Quantity.of(0.5));
    }

    /** Selling part of a holding shrinks it; what remains keeps the price it had. */
    @Test
    void shouldShrinkAPositionWhenTheAccountHoldsLess() {
        PortfolioId portfolioId = onboard();

        String specId = synchronise(portfolioId, btc(1.0, 0.3, 50_000.0, null));
        // A holding that left asks why — "I moved it to another account of mine" is an answer,
        // and one that implies no disposal to compute a result from.
        answer(specId, "transferred-in", 0.3, AnswerKind.MOVED_TO_OWN_ACCOUNT);
        controller.confirm(specId, new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, List.of(btc(1.0, 0.3, 50_000.0, null))));

        Portfolio portfolio = reload(portfolioId);
        assertThat(position(portfolio, SubName.transferredIn()).getQuantity())
                .as("1.0 held, 0.7 left")
                .isEqualTo(Quantity.of(0.7));
        assertThat(position(portfolio, SubName.traded()).getCostBasis().avgPrice()
                .getAmount().doubleValue()).isEqualTo(50_000);
    }

    /**
     * The ledger is not touched by a synchronisation. An opening contribution stands for a history
     * we never saw (C12); a second one would claim the owner paid in again what they merely kept,
     * and the wealth change (C5) would collapse to nothing.
     */
    @Test
    void shouldNotWriteASecondOpeningContribution() {
        PortfolioId portfolioId = onboard();
        assertThat(reload(portfolioId).getContributions()).hasSize(1);

        String specId = synchronise(portfolioId, btc(1.5, 0.5, 60_000.0, null));
        controller.confirm(specId, new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, List.of(btc(1.5, 0.5, 60_000.0, null))));

        assertThat(reload(portfolioId).getContributions())
                .as("still the one entry from onboarding")
                .hasSize(1)
                .allSatisfy(entry ->
                        assertThat(entry.provenance()).isEqualTo(Provenance.OPENING_SNAPSHOT));
    }

    /** The connection was put into service by the first reading; a later one leaves it alone. */
    @Test
    void shouldLeaveAConnectionAlreadyInServiceUntouched() {
        PortfolioId portfolioId = onboard();

        String specId = synchronise(portfolioId, btc(1.5, 0.5, 60_000.0, null));
        controller.confirm(specId, new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, List.of(btc(1.5, 0.5, 60_000.0, null))));

        ExchangeConnection connection = connections
                .findOwnedOrThrow(ALICE, ExchangeConnectionId.of(CONNECTION));
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(connection.getPortfolioId()).isEqualTo(portfolioId);
    }

    /** What the exchange froze is restated by each reading, not accumulated (task D5). */
    @Test
    void shouldRestateTheFreezeOnEveryReading() {
        PortfolioId portfolioId = onboard();

        String specId = synchronise(portfolioId, btc(1.5, 0.5, 60_000.0, 0.6));
        controller.confirm(specId, new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "OKX", NOW, List.of(btc(1.5, 0.5, 60_000.0, 0.6))));

        Portfolio portfolio = reload(portfolioId);
        assertThat(position(portfolio, SubName.traded()).getLocked()).isEqualTo(Quantity.of(0.5));
        assertThat(position(portfolio, SubName.transferredIn()).getLocked().getQty())
                .as("0.1 spills over onto what the exchange never priced")
                .isCloseTo(0.1, within(1e-9));
    }

    /** An account that has not moved produces no specification at all — there is nothing to decide. */
    @Test
    void shouldRefuseToBuildASpecificationWhenNothingChanged() {
        PortfolioId portfolioId = onboard();

        assertThatThrownBy(() -> synchronise(portfolioId, btc(1.3, 0.3, 50_000.0, null)))
                .isInstanceOf(NothingToSynchroniseException.class);
    }
}
