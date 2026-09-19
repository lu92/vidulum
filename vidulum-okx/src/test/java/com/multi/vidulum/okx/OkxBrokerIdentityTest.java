package com.multi.vidulum.okx;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of dropping the private {@code Exchange} enum in favour of {@link
 * com.multi.vidulum.common.Broker}: the value that identifies OKX for quotations is the same
 * value a connection stores, so the connection and the portfolio it feeds cannot end up naming
 * the exchange differently.
 *
 * <p>Before the change there were two unrelated identifiers for one exchange — {@code
 * Exchange.OKX} on the connection and {@code Broker.of("OKX")} on the portfolio — and nothing
 * checked that they agreed.
 */
class OkxBrokerIdentityTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");

    @Test
    void shouldIdentifyOkxWithTheSameBrokerForQuotationsAndConnections() {
        ExchangeConnection connection = ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-1"),
                UserId.of("U10000001"),
                OkxBrokerQuotationProvider.OKX,
                "349378528917283",
                ExchangeEnvironment.LIVE,
                OkxRegion.EEA.name(),
                ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL,
                Currency.of("EUR"),
                NOW);

        assertThat(connection.getBroker())
                .isEqualTo(new OkxBrokerQuotationProvider().getBroker());
        assertThat(connection.getRegion()).isEqualTo("EEA");
    }

    /**
     * The broker id is part of the natural key, so letter case must not split one account in two.
     */
    @Test
    void shouldNormaliseBrokerIdSoCasingCannotSplitTheNaturalKey() {
        ExchangeConnection connection = ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-1"),
                UserId.of("U10000001"),
                com.multi.vidulum.common.Broker.of("okx"),
                "349378528917283",
                ExchangeEnvironment.LIVE,
                OkxRegion.EEA.name(),
                ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL,
                Currency.of("EUR"),
                NOW);

        assertThat(connection.getBroker()).isEqualTo(OkxBrokerQuotationProvider.OKX);
    }
}
