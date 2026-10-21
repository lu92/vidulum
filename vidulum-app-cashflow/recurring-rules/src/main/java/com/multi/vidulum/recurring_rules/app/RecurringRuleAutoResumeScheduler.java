package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.recurring_rules.app.commands.autoresume.AutoResumeRuleCommand;
import com.multi.vidulum.recurring_rules.app.queries.GetRulesDueForAutoResumeQuery;
import com.multi.vidulum.recurring_rules.domain.PauseInfo;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Scheduled job for automatic resumption of paused recurring rules.
 *
 * <p>Runs daily at 03:00 UTC to find all paused rules with a resumeDate on or before today and
 * resume them, which regenerates their predictions.
 *
 * <p>It runs AFTER {@code MonthlyRolloverScheduler} (02:00 UTC) so that month rollovers are complete
 * before rules start generating cash changes for the new month.
 *
 * <p><b>It asks and tells through the gateways</b>, like every other caller in this module: one
 * {@link GetRulesDueForAutoResumeQuery} for the night's list, then one
 * {@link AutoResumeRuleCommand} per rule. A command per rule rather than one for the batch, because
 * a rule that cannot be resumed must not stop the rest - the counters below are the point of the
 * job, and a batch would have to report them anyway.
 *
 * <p>The note that used to stand here - "auto-resumed rules are set to ACTIVE but cash changes are
 * NOT automatically generated because this scheduler does not have access to user auth tokens
 * (VID-147)" - described a real defect and is no longer true: the writes stopped going out over
 * HTTP, so nothing needs a token and the predictions are regenerated (F61).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringRuleAutoResumeScheduler {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final Clock clock;

    /**
     * Scheduled job that runs at 03:00 UTC daily.
     * <p>
     * The cron expression "0 0 3 * * *" means:
     * - Second: 0
     * - Minute: 0
     * - Hour: 3 (03:00 UTC)
     * - Day of month: * (every day)
     * - Month: * (every month)
     * - Day of week: * (any)
     */
    @Scheduled(cron = "${vidulum.recurring-rules.auto-resume.cron:0 0 3 * * *}")
    public void autoResumePausedRules() {
        LocalDate today = LocalDate.now(clock);

        log.info("Starting auto-resume job for date [{}]", today);

        List<RecurringRuleSnapshot> rulesToResume =
                queryGateway.send(new GetRulesDueForAutoResumeQuery(today));

        if (rulesToResume.isEmpty()) {
            log.info("No rules to auto-resume for date [{}]", today);
            return;
        }

        log.info("Found [{}] rules to auto-resume", rulesToResume.size());

        int successCount = 0;
        int failureCount = 0;

        for (RecurringRuleSnapshot rule : rulesToResume) {
            LocalDate resumeDate = resumeDateOf(rule, today);
            try {
                commandGateway.send(new AutoResumeRuleCommand(rule.ruleId().id(), resumeDate));

                log.info("Auto-resumed rule [{}] '{}' scheduled for [{}]",
                        rule.ruleId().id(), rule.name(), resumeDate);
                successCount++;
            } catch (Exception e) {
                // one rule must not end the night's work; the handler already skips the ordinary
                // reasons (deleted, resumed by hand), so what reaches here is unexpected
                log.error("Failed to auto-resume rule [{}]: {}", rule.ruleId().id(), e.getMessage(), e);
                failureCount++;
            }
        }

        log.info("Auto-resume job completed. Success: [{}], Failures: [{}]", successCount, failureCount);
    }

    /**
     * Manual trigger for auto-resume (useful for testing or catch-up scenarios).
     * <p>
     * This method can be called programmatically to trigger auto-resume outside the scheduled time.
     */
    public void triggerManualAutoResume() {
        log.info("Manual auto-resume triggered");
        autoResumePausedRules();
    }

    /**
     * An indefinitely paused rule has no resume date, and the query would not have returned it -
     * {@code today} is a fallback for the log, not a decision.
     */
    private static LocalDate resumeDateOf(RecurringRuleSnapshot rule, LocalDate today) {
        return Optional.ofNullable(rule.pauseInfo())
                .flatMap(PauseInfo::getResumeDate)
                .orElse(today);
    }
}
