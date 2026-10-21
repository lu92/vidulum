package com.multi.vidulum.recurring_rules.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window both the executor and the delete-impact preview read.
 */
class ForecastWindowTest {

    @Test
    void itStartsTodayAndEndsWithTheTwelfthMonth() {
        ForecastWindow window = ForecastWindow.startingAt(LocalDate.of(2022, 1, 20));

        assertThat(window.from())
                .as("a rule does not predict backwards, so not the first of the month")
                .isEqualTo(LocalDate.of(2022, 1, 20));
        assertThat(window.to()).isEqualTo(LocalDate.of(2022, 12, 31));
    }

    @Test
    void itAlwaysEndsOnTheLastDayOfItsLastMonth() {
        assertThat(ForecastWindow.startingAt(LocalDate.of(2022, 3, 1)).to())
                .isEqualTo(LocalDate.of(2023, 2, 28));
        assertThat(ForecastWindow.startingAt(LocalDate.of(2023, 3, 1)).to())
                .as("the twelfth month ends in a leap February")
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void itSpansTwelveMonthsCountingTheCurrentOne() {
        ForecastWindow window = ForecastWindow.startingAt(LocalDate.of(2022, 1, 20));

        long months = YearMonth.from(window.from()).until(YearMonth.from(window.to()),
                java.time.temporal.ChronoUnit.MONTHS) + 1;

        assertThat(months).isEqualTo(ForecastWindow.months()).isEqualTo(12);
    }

    @Test
    void theLastDayOfAMonthStillGetsAFullWindow() {
        ForecastWindow window = ForecastWindow.startingAt(LocalDate.of(2022, 1, 31));

        assertThat(window.from()).isEqualTo(LocalDate.of(2022, 1, 31));
        assertThat(window.to())
                .as("the window is counted in months, so the day of the month does not shorten it")
                .isEqualTo(LocalDate.of(2022, 12, 31));
    }
}
