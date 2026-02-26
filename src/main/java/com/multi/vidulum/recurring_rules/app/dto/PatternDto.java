package com.multi.vidulum.recurring_rules.app.dto;

import com.multi.vidulum.recurring_rules.domain.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatternDto {

    private RecurrenceType type;

    // Daily
    private Integer intervalDays;

    // Weekly
    private DayOfWeek dayOfWeek;
    private Integer intervalWeeks;

    // Monthly
    private Integer dayOfMonth;
    private Integer intervalMonths;
    private Boolean adjustForMonthEnd;

    // Yearly
    private Integer month;
    private Integer yearlyDayOfMonth;

    public RecurrencePattern toPattern() {
        return switch (type) {
            case DAILY -> new DailyPattern(intervalDays != null ? intervalDays : 1);
            case WEEKLY -> new WeeklyPattern(
                    dayOfWeek != null ? dayOfWeek : DayOfWeek.MONDAY,
                    intervalWeeks != null ? intervalWeeks : 1
            );
            case MONTHLY -> new MonthlyPattern(
                    dayOfMonth != null ? dayOfMonth : 1,
                    intervalMonths != null ? intervalMonths : 1,
                    adjustForMonthEnd != null && adjustForMonthEnd
            );
            case YEARLY -> new YearlyPattern(
                    month != null ? month : 1,
                    yearlyDayOfMonth != null ? yearlyDayOfMonth : 1
            );
        };
    }

    public static PatternDto fromPattern(RecurrencePattern pattern) {
        PatternDto dto = new PatternDto();
        dto.setType(pattern.type());

        switch (pattern) {
            case DailyPattern daily -> dto.setIntervalDays(daily.intervalDays());
            case WeeklyPattern weekly -> {
                dto.setDayOfWeek(weekly.dayOfWeek());
                dto.setIntervalWeeks(weekly.intervalWeeks());
            }
            case MonthlyPattern monthly -> {
                dto.setDayOfMonth(monthly.dayOfMonth());
                dto.setIntervalMonths(monthly.intervalMonths());
                dto.setAdjustForMonthEnd(monthly.adjustForMonthEnd());
            }
            case YearlyPattern yearly -> {
                dto.setMonth(yearly.month());
                dto.setYearlyDayOfMonth(yearly.dayOfMonth());
            }
        }

        return dto;
    }
}
