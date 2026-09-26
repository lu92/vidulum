package com.multi.vidulum.pnl.app;

import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.pnl.app.commands.MakePnlSnapshotCommand;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Writes down what every portfolio is worth, once a day (task F9).
 *
 * <p>The machinery to record a valuation has existed since the PnL module was written and nothing
 * ever called it, so there was no history to read — which is why "how much did I make this month"
 * had no answer that was not invented from today's prices.
 *
 * <p>A snapshot that is never taken cannot be taken later: the price of an asset on a past
 * Tuesday is not something this application can go back for. That is what makes a scheduled job
 * the right shape here rather than computing on demand — the data has to be captured while it is
 * the present.
 *
 * <p>Failure is per owner. One portfolio whose quote is missing must not cost everybody else
 * their day's record, because the gap it leaves is permanent.
 */
@Slf4j
@Component
@AllArgsConstructor
public class DailyValuationScheduler {

    private final DomainPnlRepository repository;
    private final CommandGateway commandGateway;
    private final Clock clock;

    /** 01:00 UTC: after the day it records has ended, before anyone is likely to ask about it. */
    @Scheduled(cron = "${vidulum.valuation.cron:0 0 1 * * *}")
    public void recordTodaysValuations() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        List<UserId> owners = repository.everyOwner();

        log.info("Recording valuations at [{}] for {} owner(s)", now, owners.size());
        int recorded = 0;
        for (UserId owner : owners) {
            try {
                commandGateway.send(MakePnlSnapshotCommand.builder()
                        .userId(owner)
                        .dateTimeRange(Range.of(now.minusDays(1), now))
                        .build());
                recorded++;
            } catch (RuntimeException failure) {
                // Logged and skipped rather than propagated: the next owner's valuation is still
                // worth taking, and a day missed for one of them cannot be recovered afterwards.
                log.warn("Could not record a valuation for owner [{}]: {}",
                        owner.getId(), failure.getMessage());
            }
        }
        log.info("Recorded {} of {} valuation(s)", recorded, owners.size());
    }
}
