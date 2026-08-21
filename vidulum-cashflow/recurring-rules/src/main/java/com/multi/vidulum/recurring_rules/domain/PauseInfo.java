package com.multi.vidulum.recurring_rules.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Information about a rule pause period.
 */
public record PauseInfo(
        Instant pausedAt,
        LocalDate resumeDate,
        String reason
) {
    public PauseInfo {
        Objects.requireNonNull(pausedAt, "Paused at timestamp cannot be null");
    }

    public static PauseInfo indefinite(Instant pausedAt, String reason) {
        return new PauseInfo(pausedAt, null, reason);
    }

    public static PauseInfo untilDate(Instant pausedAt, LocalDate resumeDate, String reason) {
        Objects.requireNonNull(resumeDate, "Resume date cannot be null for timed pause");
        return new PauseInfo(pausedAt, resumeDate, reason);
    }

    public boolean hasResumeDate() {
        return resumeDate != null;
    }

    public Optional<LocalDate> getResumeDate() {
        return Optional.ofNullable(resumeDate);
    }

    public boolean shouldResumeOn(LocalDate date) {
        return resumeDate != null && !date.isBefore(resumeDate);
    }
}
