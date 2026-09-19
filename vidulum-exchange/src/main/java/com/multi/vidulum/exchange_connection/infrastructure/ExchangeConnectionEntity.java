package com.multi.vidulum.exchange_connection.infrastructure;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.ConnectionStatus;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

/**
 * Mongo representation of {@link ExchangeConnection}.
 *
 * <p>Enums and value objects are flattened to strings on the way in and rebuilt on the way out,
 * matching {@code UserFinancialProfileEntity}. The deliberate part is what is <i>not</i> here:
 * no API key, no passphrase, no secret of any kind. In the POC the backend holds no credentials
 * at all — {@code credentialsMode} records that as a decision rather than an omission.
 *
 * <p>One collection holds the connections of every exchange; {@code broker} tells them apart and
 * is part of the natural key.
 *
 * <p>The unique index over the natural key is <b>not</b> declared with {@code @CompoundIndex}.
 * Spring Data's automatic index creation is off by default and this project never turns it on,
 * so the annotation would be documentation that never reaches the database. It is created
 * explicitly in {@link ExchangeConnectionIndexInitializer}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("exchange_connections")
public class ExchangeConnectionEntity {

    @Id
    private String id;

    private String userId;
    private String broker;
    private String accountUid;
    private String environment;
    private String region;
    private String reportedKeyPermissions;
    private String credentialsMode;
    private String denominationCurrency;
    private String portfolioId;
    private String status;
    private String statusReason;
    private Date lastSnapshotAt;
    private Date lastSyncAt;
    private Date createdAt;

    public static ExchangeConnectionEntity from(ExchangeConnection connection) {
        return ExchangeConnectionEntity.builder()
                .id(connection.getId().getId())
                .userId(connection.getUserId().getId())
                .broker(connection.getBroker().getId())
                .accountUid(connection.getAccountUid())
                .environment(connection.getEnvironment().name())
                .region(connection.getRegion())
                .reportedKeyPermissions(connection.getReportedKeyPermissions().getRaw())
                .credentialsMode(connection.getCredentialsMode().name())
                .denominationCurrency(connection.getDenominationCurrency().getId())
                .portfolioId(connection.getPortfolioId() != null ? connection.getPortfolioId().getId() : null)
                .status(connection.getStatus().name())
                .statusReason(connection.getStatusReason())
                .lastSnapshotAt(toDate(connection.getLastSnapshotAt()))
                .lastSyncAt(toDate(connection.getLastSyncAt()))
                .createdAt(toDate(connection.getCreatedAt()))
                .build();
    }

    public ExchangeConnection toDomain() {
        return ExchangeConnection.builder()
                .id(ExchangeConnectionId.of(id))
                .userId(UserId.of(userId))
                .broker(Broker.of(broker))
                .accountUid(accountUid)
                .environment(ExchangeEnvironment.valueOf(environment))
                .region(region)
                .reportedKeyPermissions(ReportedKeyPermissions.of(reportedKeyPermissions))
                .credentialsMode(CredentialsMode.valueOf(credentialsMode))
                .denominationCurrency(Currency.of(denominationCurrency))
                .portfolioId(portfolioId != null ? PortfolioId.of(portfolioId) : null)
                .status(ConnectionStatus.valueOf(status))
                .statusReason(statusReason)
                .lastSnapshotAt(toZonedDateTime(lastSnapshotAt))
                .lastSyncAt(toZonedDateTime(lastSyncAt))
                .createdAt(toZonedDateTime(createdAt))
                .build();
    }

    private static Date toDate(ZonedDateTime zdt) {
        return zdt != null ? Date.from(zdt.toInstant()) : null;
    }

    private static ZonedDateTime toZonedDateTime(Date date) {
        return date != null ? ZonedDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC) : null;
    }
}
