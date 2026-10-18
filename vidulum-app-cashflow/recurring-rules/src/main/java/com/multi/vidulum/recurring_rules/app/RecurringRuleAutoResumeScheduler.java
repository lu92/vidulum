package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Scheduled job for automatic resumption of paused recurring rules.
 * <p>
 * Runs daily at 03:00 UTC to find all paused rules with a resumeDate on or before today
 * and automatically resumes them.
 * <p>
 * This scheduler runs AFTER {@code MonthlyRolloverScheduler} (02:00 UTC) to ensure
 * that month rollovers are complete before rules are resumed and start generating
 * cash changes for the new month.
 * <p>
 * Note: Currently, auto-resumed rules are set to ACTIVE status but cash changes
 * are NOT automatically generated because this scheduler does not have access to
 * user auth tokens. Cash changes will be generated:
 * <ul>
 *   <li>When the user manually triggers regeneration</li>
 *   <li>When a system token mechanism is implemented (VID-147)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringRuleAutoResumeScheduler {

    private final RecurringRuleService ruleService;
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

        List<RecurringRule> rulesToResume = ruleService.findRulesForAutoResume(today);

        if (rulesToResume.isEmpty()) {
            log.info("No rules to auto-resume for date [{}]", today);
            return;
        }

        log.info("Found [{}] rules to auto-resume", rulesToResume.size());

        int successCount = 0;
        int failureCount = 0;

        for (RecurringRule rule : rulesToResume) {
            try {
                LocalDate resumeDate = rule.getPauseInfo()
                        .flatMap(pi -> pi.getResumeDate())
                        .orElse(today);

                ruleService.handleAutoResume(rule.getRuleId(), resumeDate);

                log.info("Auto-resumed rule [{}] '{}' scheduled for [{}]",
                        rule.getRuleId().id(), rule.getName(), resumeDate);
                successCount++;
            } catch (Exception e) {
                log.error("Failed to auto-resume rule [{}]: {}",
                        rule.getRuleId().id(), e.getMessage(), e);
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
}
