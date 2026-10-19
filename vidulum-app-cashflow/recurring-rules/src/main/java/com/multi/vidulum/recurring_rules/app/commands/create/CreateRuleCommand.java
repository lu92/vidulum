package com.multi.vidulum.recurring_rules.app.commands.create;

import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.RecurrencePattern;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

/**
 * <p><b>{@code authToken} is a field because {@link com.multi.vidulum.shared.cqrs.commands.CommandHandler}
 * takes the command and nothing else.</b> It is not an improvement - a bearer token has no business
 * in a command - but it is where the transport already was: it used to be a second parameter of the
 * service method. It is here because the writes still go out over HTTP, and it disappears when they
 * stop (task F26).
 */
public record CreateRuleCommand(
        String userId,
        String cashFlowId,
        String name,
        String description,
        Money baseAmount,
        CategoryName categoryName,
        RecurrencePattern pattern,
        LocalDate startDate,
        LocalDate endDate,
        Integer maxOccurrences,
        List<Month> activeMonths,
        List<LocalDate> excludedDates,
        String authToken
) implements Command {
}
