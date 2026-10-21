package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.shared.cqrs.queries.Query;

import java.time.LocalDate;

/**
 * Paused rules whose resume date has arrived, as of {@code date}.
 *
 * <p>Takes the date rather than reading a clock, because the scheduler already decided which day it
 * is working on - and a catch-up run for a night that was missed asks about that night, not today.
 */
public record GetRulesDueForAutoResumeQuery(LocalDate date) implements Query {
}
