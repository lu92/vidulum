package com.multi.vidulum.okx.exchange_connection;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.okx.exchange_connection.domain.ConnectionStatus;
import com.multi.vidulum.okx.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.okx.exchange_connection.domain.Exchange;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeRegion;
import com.multi.vidulum.okx.exchange_connection.domain.ReportedKeyPermissions;
import com.multi.vidulum.okx.exchange_connection.infrastructure.ExchangeConnectionEntity;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping is hand-written, so every field is one typo away from being silently dropped.
 * These tests compare whole objects rather than fields: adding a field to
 * {@link ExchangeConnection} without extending the mapping fails here instead of going unnoticed.
 */
class ExchangeConnectionEntityTest {

    private static final ZonedDateTime CREATED = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final ZonedDateTime SNAPSHOT_AT = ZonedDateTime.parse("2022-03-04T10:15:30Z");
    private static final ZonedDateTime SYNC_AT = ZonedDateTime.parse("2022-03-04T10:16:00Z");

    private static ExchangeConnection activeConnection() {
        ExchangeConnection connection = ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-1"),
                UserId.of("U10000001"),
                Exchange.OKX,
                "349378528917283",
                ExchangeEnvironment.LIVE,
                ExchangeRegion.EEA,
                ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL,
                Currency.of("EUR"),
                CREATED);
        connection.confirm(PortfolioId.of("portfolio-1"), SYNC_AT);
        connection.recordSnapshot(SNAPSHOT_AT);
        return connection;
    }

    @Test
    void shouldSurviveRoundTripWhenActive() {
        ExchangeConnection original = activeConnection();

        ExchangeConnection restored = ExchangeConnectionEntity.from(original).toDomain();

        assertThat(restored).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void shouldSurviveRoundTripWhenPendingWithNoPortfolioAndNoTimestamps() {
        ExchangeConnection original = ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-2"),
                UserId.of("U10000002"),
                Exchange.OKX,
                "111222333",
                ExchangeEnvironment.DEMO,
                ExchangeRegion.GLOBAL,
                ReportedKeyPermissions.of("read_only"),
                CredentialsMode.STORED_ENCRYPTED,
                Currency.of("PLN"),
                CREATED);

        ExchangeConnection restored = ExchangeConnectionEntity.from(original).toDomain();

        assertThat(restored).usingRecursiveComparison().isEqualTo(original);
        assertThat(restored.getPortfolioId()).isNull();
        assertThat(restored.getLastSyncAt()).isNull();
        assertThat(restored.getLastSnapshotAt()).isNull();
    }

    @Test
    void shouldSurviveRoundTripWhenRevoked() {
        ExchangeConnection original = activeConnection();
        original.revoke("api key expired", SYNC_AT);

        ExchangeConnection restored = ExchangeConnectionEntity.from(original).toDomain();

        assertThat(restored).usingRecursiveComparison().isEqualTo(original);
        assertThat(restored.getStatus()).isEqualTo(ConnectionStatus.REVOKED);
        assertThat(restored.getStatusReason()).isEqualTo("api key expired");
        assertThat(restored.getPortfolioId()).isEqualTo(PortfolioId.of("portfolio-1"));
    }

    /**
     * The entity is the one place a secret could leak into the database. Compare the whole
     * document so that adding any field to it forces a decision here.
     */
    @Test
    void shouldStoreOnlyTheDeclaredFieldsAndNoCredentials() {
        ExchangeConnection original = activeConnection();

        ExchangeConnectionEntity entity = ExchangeConnectionEntity.from(original);

        assertThat(entity)
                .usingRecursiveComparison()
                .isEqualTo(new ExchangeConnectionEntity(
                        "conn-1",
                        "U10000001",
                        "OKX",
                        "349378528917283",
                        "LIVE",
                        "EEA",
                        "read_only",
                        "EXTERNAL",
                        "EUR",
                        "portfolio-1",
                        "ACTIVE",
                        null,
                        Date.from(SNAPSHOT_AT.toInstant()),
                        Date.from(SYNC_AT.toInstant()),
                        Date.from(CREATED.toInstant())));
    }
}
