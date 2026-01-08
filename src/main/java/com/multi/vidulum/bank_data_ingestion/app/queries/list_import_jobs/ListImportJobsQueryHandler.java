package com.multi.vidulum.bank_data_ingestion.app.queries.list_import_jobs;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJob;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobRepository;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Handler for listing import jobs.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ListImportJobsQueryHandler implements QueryHandler<ListImportJobsQuery, ListImportJobsResult> {

    private final ImportJobRepository importJobRepository;
    private final Clock clock;

    @Override
    public ListImportJobsResult query(ListImportJobsQuery query) {
        List<ImportJob> jobs;

        if (query.statuses() == null || query.statuses().isEmpty()) {
            jobs = importJobRepository.findByCashFlowId(query.cashFlowId());
        } else {
            jobs = importJobRepository.findByCashFlowIdAndStatusIn(query.cashFlowId(), query.statuses());
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        log.debug("Found {} import jobs for CashFlow [{}]", jobs.size(), query.cashFlowId().id());

        return ListImportJobsResult.from(query.cashFlowId(), jobs, now);
    }
}
