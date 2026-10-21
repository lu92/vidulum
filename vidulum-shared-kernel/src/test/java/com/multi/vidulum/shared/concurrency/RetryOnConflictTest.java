package com.multi.vidulum.shared.concurrency;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a scheduled job is allowed to do with a conflict.
 */
class RetryOnConflictTest {

    @Test
    void workThatSucceedsFirstTimeRunsOnce() {
        AtomicInteger attempts = new AtomicInteger();

        String answer = RetryOnConflict.call(3, "rollover", () -> {
            attempts.incrementAndGet();
            return "done";
        });

        assertThat(answer).isEqualTo("done");
        assertThat(attempts).hasValue(1);
    }

    /**
     * The case the helper exists for: somebody else wrote first, the second read sees their state,
     * and the work goes through.
     */
    @Test
    void aConflictIsTriedAgainAndTheSecondAttemptCounts() {
        AtomicInteger attempts = new AtomicInteger();

        String answer = RetryOnConflict.call(3, "rollover", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new OptimisticLockingFailureException("someone else got there first");
            }
            return "done";
        });

        assertThat(answer).isEqualTo("done");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void aConflictThatNeverClearsIsRaisedAfterTheLastAttempt() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> RetryOnConflict.call(3, "rollover", () -> {
            attempts.incrementAndGet();
            throw new OptimisticLockingFailureException("still losing");
        }))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessage("still losing");

        assertThat(attempts)
                .as("tried the number of times asked for, not once more")
                .hasValue(3);
    }

    /**
     * Anything that is not a conflict means the work itself is broken, and repeating it would turn
     * one failure into several.
     */
    @Test
    void anyOtherFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> RetryOnConflict.run(3, "rollover", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("broken");
        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void askingForNoAttemptsIsAProgrammingMistake() {
        assertThatThrownBy(() -> RetryOnConflict.run(0, "rollover", () -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }
}
