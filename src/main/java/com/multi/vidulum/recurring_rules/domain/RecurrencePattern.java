package com.multi.vidulum.recurring_rules.domain;

import java.time.LocalDate;

/**
 * Sealed interface representing different recurrence patterns for recurring rules.
 */
public sealed interface RecurrencePattern
        permits DailyPattern, WeeklyPattern, MonthlyPattern, YearlyPattern {

    RecurrenceType type();

    LocalDate nextOccurrenceFrom(LocalDate fromDate);

    boolean isValidForDate(LocalDate date);

    String toDisplayString();
}
