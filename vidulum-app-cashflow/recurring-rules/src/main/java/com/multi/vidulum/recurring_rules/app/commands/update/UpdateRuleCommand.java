package com.multi.vidulum.recurring_rules.app.commands.update;

import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.RecurrencePattern;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

/**
 * <p>No {@code authToken}, and it is worth saying why it ever had one: a bearer token has no
 * business in a command, but it was where the transport had got to - the handler passed it to an
 * HTTP client that wrote to cashflow once per occurrence. The javadoc here said the field would
 * disappear when those writes stopped. They stopped (F58, F59, F60), so it did.
 */
public record UpdateRuleCommand(
        String ruleId,
        String name,
        String description,
        Money baseAmount,
        CategoryName categoryName,
        RecurrencePattern pattern,
        LocalDate startDate,
        LocalDate endDate,
        Integer maxOccurrences,
        List<Month> activeMonths,
        List<LocalDate> excludedDates
) implements Command {
}
