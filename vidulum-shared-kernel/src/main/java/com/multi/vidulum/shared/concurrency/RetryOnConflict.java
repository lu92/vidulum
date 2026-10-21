package com.multi.vidulum.shared.concurrency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.function.Supplier;

/**
 * Runs a unit of work again when optimistic locking refused it (task F57).
 *
 * <p><b>Why this exists at all.</b> Putting {@code @Version} on an entity turns a lost update from a
 * silent win into a visible failure, which is right for a request - the caller gets a 409 and can
 * decide. It is wrong for a scheduled job: nobody is there to decide, so the night's turn would be
 * dropped with a log line, and the month that should have rolled over simply does not.
 *
 * <p><b>What may be retried.</b> Only a unit that <em>re-reads</em> inside itself. Every caller here
 * sends a command whose handler loads the aggregate again, so the second attempt works from the
 * winner's state, not from the state that was refused. A unit that decided something from a stale
 * read - "this transaction is still pending, so confirm it" - must <b>not</b> be wrapped: re-running
 * it would reapply a decision whose premise is gone. That is why this is a helper a caller opts into,
 * and not something hidden inside the repositories.
 *
 * <p>Nothing but {@link OptimisticLockingFailureException} is retried. A conflict means "someone
 * else got there first, look again"; anything else means the work itself is broken, and repeating it
 * would only turn one failure into several.
 */
@Slf4j
public final class RetryOnConflict {

    private RetryOnConflict() {
    }

    /**
     * @param attempts how many times to try in total, including the first - must be at least 1
     * @param what     short description for the log, so a retry says what was retried
     * @param unit     the work; it must re-read whatever it changes
     */
    public static <T> T call(int attempts, String what, Supplier<T> unit) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was: " + attempts);
        }

        OptimisticLockingFailureException lastConflict = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return unit.get();
            } catch (OptimisticLockingFailureException conflict) {
                lastConflict = conflict;
                log.warn("Conflict on [{}], attempt {} of {}: {}", what, attempt, attempts, conflict.getMessage());
            }
        }

        throw lastConflict;
    }

    public static void run(int attempts, String what, Runnable unit) {
        call(attempts, what, () -> {
            unit.run();
            return null;
        });
    }
}
