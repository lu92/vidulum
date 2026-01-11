package com.multi.vidulum.bank_data_ingestion.app.queries.list_staging_sessions;

import com.multi.vidulum.bank_data_ingestion.domain.StagedTransaction;
import com.multi.vidulum.bank_data_ingestion.domain.StagedTransactionRepository;
import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.bank_data_ingestion.domain.ValidationStatus;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handler for listing active staging sessions for a CashFlow.
 * Groups staged transactions by session and returns summary for each.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ListStagingSessionsQueryHandler
        implements QueryHandler<ListStagingSessionsQuery, ListStagingSessionsResult> {

    private final StagedTransactionRepository stagedTransactionRepository;
    private final Clock clock;

    @Override
    public ListStagingSessionsResult query(ListStagingSessionsQuery query) {
        // Get all staged transactions for this CashFlow
        List<StagedTransaction> allTransactions = stagedTransactionRepository.findByCashFlowId(query.cashFlowId());

        ZonedDateTime now = ZonedDateTime.now(clock);

        // Group by staging session and filter out expired sessions
        Map<StagingSessionId, List<StagedTransaction>> transactionsBySession = allTransactions.stream()
                .filter(tx -> tx.expiresAt().isAfter(now)) // Only non-expired
                .collect(Collectors.groupingBy(StagedTransaction::stagingSessionId));

        // Build summary for each session
        List<ListStagingSessionsResult.StagingSessionSummary> summaries = transactionsBySession.entrySet().stream()
                .map(entry -> buildSessionSummary(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ListStagingSessionsResult.StagingSessionSummary::createdAt).reversed()) // Most recent first
                .toList();

        boolean hasPendingImport = !summaries.isEmpty();

        log.info("Found {} active staging sessions for CashFlow [{}]",
                summaries.size(), query.cashFlowId().id());

        return new ListStagingSessionsResult(
                query.cashFlowId(),
                summaries,
                hasPendingImport
        );
    }

    private ListStagingSessionsResult.StagingSessionSummary buildSessionSummary(
            StagingSessionId sessionId,
            List<StagedTransaction> transactions) {

        // Get createdAt and expiresAt from first transaction (all in same session should have same values)
        StagedTransaction first = transactions.get(0);
        ZonedDateTime createdAt = first.createdAt();
        ZonedDateTime expiresAt = first.expiresAt();

        // Count by validation status
        int total = transactions.size();
        int valid = (int) transactions.stream()
                .filter(tx -> tx.validation().status() == ValidationStatus.VALID)
                .count();
        int invalid = (int) transactions.stream()
                .filter(tx -> tx.validation().status() == ValidationStatus.INVALID)
                .count();
        int duplicate = (int) transactions.stream()
                .filter(tx -> tx.validation().status() == ValidationStatus.DUPLICATE)
                .count();

        // Determine session status
        String status = determineStatus(valid, invalid, total);

        return new ListStagingSessionsResult.StagingSessionSummary(
                sessionId,
                status,
                createdAt,
                expiresAt,
                new ListStagingSessionsResult.TransactionCounts(total, valid, invalid, duplicate)
        );
    }

    private String determineStatus(int valid, int invalid, int total) {
        if (valid == total) {
            return "READY_FOR_IMPORT";
        } else if (valid > 0) {
            return "PARTIALLY_VALID";
        } else if (invalid == total) {
            return "ALL_INVALID";
        } else {
            return "PENDING_REVIEW";
        }
    }
}
