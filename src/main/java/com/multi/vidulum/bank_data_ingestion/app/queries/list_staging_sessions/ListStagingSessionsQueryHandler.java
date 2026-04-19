package com.multi.vidulum.bank_data_ingestion.app.queries.list_staging_sessions;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionStatus;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagingSessionMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagingSessionEntity;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Handler for listing active staging sessions for a CashFlow.
 * Uses StagingSessionEntity for efficient querying instead of computing from transactions.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ListStagingSessionsQueryHandler
        implements QueryHandler<ListStagingSessionsQuery, ListStagingSessionsResult> {

    private final StagingSessionMongoRepository stagingSessionRepository;
    private final Clock clock;

    @Override
    public ListStagingSessionsResult query(ListStagingSessionsQuery query) {
        // Get all staging sessions for this CashFlow (MongoDB TTL index handles expiration)
        List<StagingSessionEntity> sessions = stagingSessionRepository
                .findByCashFlowId(query.cashFlowId().id());

        Instant now = Instant.now(clock);

        // Build summary for each session, filtering out expired ones
        // Sort by createdAt descending, then by sessionId descending for stable ordering
        List<ListStagingSessionsResult.StagingSessionSummary> summaries = sessions.stream()
                .filter(session -> session.getExpiresAt().isAfter(now)) // Only non-expired
                .map(this::buildSessionSummary)
                .sorted(Comparator
                        .comparing(ListStagingSessionsResult.StagingSessionSummary::createdAt).reversed()
                        .thenComparing(s -> s.stagingSessionId().id(), Comparator.reverseOrder()))
                .toList();

        // Check if there's any session that could be imported (not COMPLETED or IMPORTING)
        boolean hasPendingImport = summaries.stream()
                .anyMatch(s -> !s.status().equals(StagingSessionStatus.COMPLETED.name()) &&
                               !s.status().equals(StagingSessionStatus.IMPORTING.name()));

        log.info("Found {} active staging sessions for CashFlow [{}]",
                summaries.size(), query.cashFlowId().id());

        return new ListStagingSessionsResult(
                query.cashFlowId(),
                summaries,
                hasPendingImport
        );
    }

    private ListStagingSessionsResult.StagingSessionSummary buildSessionSummary(
            StagingSessionEntity session) {

        return new ListStagingSessionsResult.StagingSessionSummary(
                new StagingSessionId(session.getSessionId()),
                session.getStatus().name(),
                toZonedDateTime(session.getCreatedAt()),
                toZonedDateTime(session.getExpiresAt()),
                new ListStagingSessionsResult.TransactionCounts(
                        session.getTotalTransactions(),
                        session.getValidTransactions(),
                        session.getInvalidTransactions(),
                        session.getDuplicateTransactions()
                )
        );
    }

    private ZonedDateTime toZonedDateTime(Instant instant) {
        return instant != null ? instant.atZone(ZoneId.of("UTC")) : null;
    }
}
