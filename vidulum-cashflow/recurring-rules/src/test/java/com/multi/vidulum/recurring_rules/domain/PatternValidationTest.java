package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRecurrencePatternException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatternValidationTest {

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForMonthlyPatternWithInvalidDayOfMonth() {
        assertThatThrownBy(() -> new MonthlyPattern(35, 1, false))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("MONTHLY")
                .hasMessageContaining("Day of month must be between 1 and 31");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForMonthlyPatternWithInvalidInterval() {
        assertThatThrownBy(() -> new MonthlyPattern(15, 13, false))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("MONTHLY")
                .hasMessageContaining("Interval must be between 1 and 12 months");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForDailyPatternWithInvalidInterval() {
        assertThatThrownBy(() -> new DailyPattern(0))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("DAILY")
                .hasMessageContaining("Interval must be between 1 and 365 days");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForWeeklyPatternWithNullDayOfWeek() {
        assertThatThrownBy(() -> new WeeklyPattern(null, 1))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("WEEKLY")
                .hasMessageContaining("Day of week cannot be null");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForWeeklyPatternWithInvalidInterval() {
        assertThatThrownBy(() -> new WeeklyPattern(DayOfWeek.MONDAY, 53))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("WEEKLY")
                .hasMessageContaining("Interval must be between 1 and 52 weeks");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForYearlyPatternWithInvalidMonth() {
        assertThatThrownBy(() -> new YearlyPattern(13, 15))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("YEARLY")
                .hasMessageContaining("Month must be between 1 and 12");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForYearlyPatternWithInvalidDayOfMonth() {
        assertThatThrownBy(() -> new YearlyPattern(6, 32))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("YEARLY")
                .hasMessageContaining("Day of month must be between 1 and 31");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForQuarterlyPatternWithInvalidMonthInQuarter() {
        assertThatThrownBy(() -> new QuarterlyPattern(4, 15))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("QUARTERLY")
                .hasMessageContaining("Month in quarter must be 1, 2, or 3");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForQuarterlyPatternWithInvalidDayOfMonth() {
        assertThatThrownBy(() -> new QuarterlyPattern(2, 35))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("QUARTERLY")
                .hasMessageContaining("Day of month must be between 1 and 31");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForEveryNDaysPatternWithInvalidInterval() {
        assertThatThrownBy(() -> new EveryNDaysPattern(400, null))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("EVERY_N_DAYS")
                .hasMessageContaining("Interval must be between 1 and 365 days");
    }

    @Test
    void shouldThrowInvalidRecurrencePatternExceptionForOncePatternWithNullTargetDate() {
        assertThatThrownBy(() -> new OncePattern(null))
                .isInstanceOf(InvalidRecurrencePatternException.class)
                .hasMessageContaining("ONCE")
                .hasMessageContaining("Target date is required");
    }

    // Verify valid patterns don't throw
    @Test
    void shouldCreateValidMonthlyPattern() {
        MonthlyPattern pattern = new MonthlyPattern(15, 1, false);
        assertThat(pattern.dayOfMonth()).isEqualTo(15);
        assertThat(pattern.intervalMonths()).isEqualTo(1);
    }

    @Test
    void shouldCreateValidMonthlyPatternWithLastDayOfMonth() {
        MonthlyPattern pattern = new MonthlyPattern(-1, 1, false);
        assertThat(pattern.isLastDayOfMonth()).isTrue();
    }
}
