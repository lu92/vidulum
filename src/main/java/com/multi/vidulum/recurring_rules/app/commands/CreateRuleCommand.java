package com.multi.vidulum.recurring_rules.app.commands;

import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.RecurrencePattern;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.LocalDate;

public record CreateRuleCommand(
        String userId,
        String cashFlowId,
        String name,
        String description,
        Money baseAmount,
        CategoryName categoryName,
        RecurrencePattern pattern,
        LocalDate startDate,
        LocalDate endDate
) implements Command {
}
