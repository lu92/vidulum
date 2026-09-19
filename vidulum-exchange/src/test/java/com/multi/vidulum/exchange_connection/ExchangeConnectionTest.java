package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.ConnectionStatus;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import com.multi.vidulum.exchange_connection.domain.KeyPermissionsTooBroadException;
import com.multi.vidulum.exchange_connection.domain.IllegalConnectionTransitionException;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the connection lifecycle. No Spring, no Mongo — every rule here is enforced by
 * the aggregate itself, and that is where it must hold regardless of how it is stored.
 */
class ExchangeConnectionTest {

    /** Declared here, not imported: this module must not depend on any exchange's module. */
    private static final Broker OKX = Broker.of("OKX");

    private static final ZonedDateTime DAY_1 = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final ZonedDateTime DAY_2 = ZonedDateTime.parse("2022-01-02T00:00:00Z");
    private static final ZonedDateTime DAY_3 = ZonedDateTime.parse("2022-01-03T00:00:00Z");
    private static final ZonedDateTime DAY_4 = ZonedDateTime.parse("2022-01-04T00:00:00Z");

    private static final UserId USER = UserId.of("U10000001");
    private static final PortfolioId PORTFOLIO = PortfolioId.of("portfolio-1");
    private static final String ACCOUNT_UID = "349378528917283";

    private static ExchangeConnection pendingConnection() {
        return ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-1"),
                USER,
                OKX,
                ACCOUNT_UID,
                ExchangeEnvironment.DEMO,
                "EEA",
                ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL,
                Currency.of("EUR"),
                DAY_1);
    }

    @Test
    void shouldStartPendingWithoutPortfolio() {
        ExchangeConnection connection = pendingConnection();

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(connection.getPortfolioId()).isNull();
        assertThat(connection.isOnboarded()).isFalse();
        assertThat(connection.isActive()).isFalse();
        assertThat(connection.getCreatedAt()).isEqualTo(DAY_1);
        assertThat(connection.getLastSyncAt()).isNull();
        assertThat(connection.getLastSnapshotAt()).isNull();
    }

    @Test
    void shouldRejectBlankAccountUid() {
        assertThatThrownBy(() -> ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-1"), USER, OKX, "  ",
                ExchangeEnvironment.DEMO, "EEA", ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL, Currency.of("EUR"), DAY_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountUid");
    }

    @Test
    void shouldRejectMissingDenominationCurrency() {
        assertThatThrownBy(() -> ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-1"), USER, OKX, ACCOUNT_UID,
                ExchangeEnvironment.DEMO, "EEA", ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL, null, DAY_1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("denominationCurrency");
    }

    /**
     * The read-only rule is enforced by {@link ReportedKeyPermissions}, so the aggregate cannot
     * be built around a key that was allowed to trade — there is no path that skips the check.
     */
    @Test
    void shouldRefuseConnectionWhoseKeyCouldTrade() {
        assertThatThrownBy(() -> ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-1"), USER, OKX, ACCOUNT_UID,
                ExchangeEnvironment.DEMO, "EEA",
                ReportedKeyPermissions.of("read_only,trade"),
                CredentialsMode.EXTERNAL, Currency.of("EUR"), DAY_1))
                .isInstanceOf(KeyPermissionsTooBroadException.class);
    }

    @Test
    void shouldRefuseConnectionWithoutReportedPermissions() {
        assertThatThrownBy(() -> ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-1"), USER, OKX, ACCOUNT_UID,
                ExchangeEnvironment.DEMO, "EEA", null,
                CredentialsMode.EXTERNAL, Currency.of("EUR"), DAY_1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reportedKeyPermissions");
    }

    @Test
    void shouldAttachPortfolioOnConfirm() {
        ExchangeConnection connection = pendingConnection();

        connection.confirm(PORTFOLIO, DAY_2);

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(connection.getPortfolioId()).isEqualTo(PORTFOLIO);
        assertThat(connection.isOnboarded()).isTrue();
        assertThat(connection.getLastSyncAt()).isEqualTo(DAY_2);
    }

    @Test
    void shouldRefuseSecondConfirm() {
        ExchangeConnection connection = pendingConnection();
        connection.confirm(PORTFOLIO, DAY_2);

        assertThatThrownBy(() -> connection.confirm(PortfolioId.of("portfolio-2"), DAY_3))
                .isInstanceOf(IllegalConnectionTransitionException.class)
                .hasMessageContaining("ACTIVE");

        assertThat(connection.getPortfolioId()).isEqualTo(PORTFOLIO);
    }

    @Test
    void shouldKeepPortfolioWhenRevoked() {
        ExchangeConnection connection = pendingConnection();
        connection.confirm(PORTFOLIO, DAY_2);

        connection.revoke("api key expired", DAY_3);

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.REVOKED);
        assertThat(connection.getStatusReason()).isEqualTo("api key expired");
        assertThat(connection.getPortfolioId()).isEqualTo(PORTFOLIO);
        assertThat(connection.getAccountUid()).isEqualTo(ACCOUNT_UID);
        assertThat(connection.isOnboarded()).isTrue();
        assertThat(connection.isActive()).isFalse();
    }

    /**
     * The decision that motivates the whole state machine: losing the exchange is an
     * interruption, not an ending.
     */
    @Test
    void shouldReconnectRevokedConnectionKeepingItsPortfolio() {
        ExchangeConnection connection = pendingConnection();
        connection.confirm(PORTFOLIO, DAY_2);
        connection.revoke("api key expired", DAY_3);

        connection.reconnect(DAY_4);

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(connection.getStatusReason()).isNull();
        assertThat(connection.getPortfolioId()).isEqualTo(PORTFOLIO);
        assertThat(connection.getLastSyncAt()).isEqualTo(DAY_4);
    }

    @Test
    void shouldRefuseReconnectOfConnectionThatWasNeverConfirmed() {
        ExchangeConnection connection = pendingConnection();

        assertThatThrownBy(() -> connection.reconnect(DAY_2))
                .isInstanceOf(IllegalConnectionTransitionException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void shouldRefuseRevokeOfPendingConnection() {
        ExchangeConnection connection = pendingConnection();

        assertThatThrownBy(() -> connection.revoke("whatever", DAY_2))
                .isInstanceOf(IllegalConnectionTransitionException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void shouldRecoverFromErrorBackToPending() {
        ExchangeConnection connection = pendingConnection();

        connection.fail("exchange answered 401", DAY_2);
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ERROR);
        assertThat(connection.getStatusReason()).isEqualTo("exchange answered 401");

        connection.retry();

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(connection.getStatusReason()).isNull();
    }

    @Test
    void shouldReplaceReasonWhenFailingTwice() {
        ExchangeConnection connection = pendingConnection();
        connection.fail("first reason", DAY_2);

        assertThatCode(() -> connection.fail("second reason", DAY_3)).doesNotThrowAnyException();

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ERROR);
        assertThat(connection.getStatusReason()).isEqualTo("second reason");
        assertThat(connection.getLastSyncAt()).isEqualTo(DAY_2);
    }

    @Test
    void shouldRefuseRetryOfConnectionThatIsNotInError() {
        ExchangeConnection connection = pendingConnection();

        assertThatThrownBy(connection::retry)
                .isInstanceOf(IllegalConnectionTransitionException.class)
                .hasMessageContaining("PENDING");
    }

    /**
     * Reading the account and applying what was read are separate events, and the two timestamps
     * are allowed to drift apart in both directions.
     */
    @Test
    void shouldTrackSnapshotAndSyncIndependently() {
        ExchangeConnection connection = pendingConnection();
        connection.confirm(PORTFOLIO, DAY_2);

        connection.recordSnapshot(DAY_3);

        assertThat(connection.getLastSnapshotAt()).isEqualTo(DAY_3);
        assertThat(connection.getLastSyncAt()).isEqualTo(DAY_2);

        connection.recordSync(DAY_4);

        assertThat(connection.getLastSnapshotAt()).isEqualTo(DAY_3);
        assertThat(connection.getLastSyncAt()).isEqualTo(DAY_4);
    }
}
