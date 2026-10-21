package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.recurring_rules.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringRuleService {

    private final DomainRecurringRuleRepository ruleRepository;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    // Fourteen handle(...) methods left this class: eight to app/commands/{create,update,pause,
    // resume,delete,generate,addamountchange,removeamountchange} and six to app/queries, each with
    // a handler beside its command or query - the shape cashflow has used from the start. The
    // writes left their shared half behind as RecurringRuleExecutor and the reads as
    // UpcomingTransactionsCalculator, which is what the dashboard and the upcoming list had in
    // common when one of them called the other.
    //
    // What remains is the scheduler's path, and it is not a command: nobody is waiting for an
    // answer, so it skips with a warning where the equivalent handler refuses. RecurringRulesController
    // does not hold this class any more - a @Scheduled job does.

    /**
     * Auto-resumes a paused rule. Called by the scheduler when resumeDate is reached.
     *
     * <p>It does what {@code ResumeRuleCommandHandler} does, minus the refusals: a rule that has
     * been deleted or resumed by hand since the scheduler read it is skipped, not an error.
     *
     * <p><b>It now regenerates the predictions, which it used not to.</b> The comment here said
     * "cash changes are NOT generated because we don't have an auth token" - true while the writes
     * went out over HTTP, and left an auto-resumed rule ACTIVE but predicting nothing until someone
     * touched it by hand. The executor takes no token any more, so the reason is gone.
     *
     * @param ruleId the rule ID to auto-resume
     * @param resumeDate the date on which the rule should be resumed (for logging)
     */
    public void handleAutoResume(RecurringRuleId ruleId, LocalDate resumeDate) {
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElse(null);

        if (rule == null) {
            log.warn("Auto-resume: Rule {} not found, skipping", ruleId.id());
            return;
        }

        if (!rule.isPaused()) {
            log.warn("Auto-resume: Rule {} is not paused (status: {}), skipping", ruleId.id(), rule.getStatus());
            return;
        }

        rule.resume(clock.instant());
        ruleRepository.save(rule);

        executor.clear(rule);
        executor.generate(rule);

        log.info("Auto-resumed rule {} as of {} (predictions regenerated)", ruleId.id(), resumeDate);
    }

    /**
     * Returns all paused rules that should be auto-resumed on or before the given date.
     */
    public List<RecurringRule> findRulesForAutoResume(LocalDate date) {
        return ruleRepository.findPausedRulesWithResumeDateOnOrBefore(date);
    }

}
