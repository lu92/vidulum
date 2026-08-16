package com.multi.vidulum.bank_data_ingestion.app.queries.get_import_progress;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJob;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobNotFoundException;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobRepository;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * Handler for getting import job progress.
 */
@Slf4j
@Component
@AllArgsConstructor
public class GetImportProgressQueryHandler implements QueryHandler<GetImportProgressQuery, GetImportProgressResult> {

    private final ImportJobRepository importJobRepository;
    private final Clock clock;

    @Override
    public GetImportProgressResult query(GetImportProgressQuery query) {
        ImportJob job = importJobRepository.findById(query.jobId())
                .orElseThrow(() -> new ImportJobNotFoundException(query.jobId()));

        // Verify the job belongs to the requested CashFlow
        if (!job.cashFlowId().equals(query.cashFlowId())) {
            throw new ImportJobNotFoundException(query.jobId());
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        log.debug("Retrieved import job [{}] with status [{}], progress [{}%]",
                job.jobId().id(), job.status(), job.progress().percentage());

        return GetImportProgressResult.from(job, now);
    }
}
