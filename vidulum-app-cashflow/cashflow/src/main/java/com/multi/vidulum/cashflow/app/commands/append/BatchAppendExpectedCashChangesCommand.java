package com.multi.vidulum.cashflow.app.commands.append;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Append many expected cash changes as one atomic operation.
 *
 * <p><b>No ids in here.</b> The handler reserves them in one go - see
 * {@code BusinessIdGenerator.generateCashChangeIds} - and answers with them in the order of
 * {@code changes}. A caller that minted them itself would be paying one round trip to the sequence
 * per item, which is half of what this command exists to avoid.
 *
 * <p>{@code sourceRuleId} is on the batch rather than on each item, because a batch comes from one
 * recurring rule. The moment of appending is not here at all: the handler stamps it from its own
 * clock, once for the whole batch, the same way {@code BatchDeleteExpectedCashChangesCommand}
 * leaves it to its handler.
 */
public record BatchAppendExpectedCashChangesCommand(
        CashFlowId cashFlowId,
        String sourceRuleId,
        List<ExpectedCashChangeToAppend> changes
) implements Command {

    public record ExpectedCashChangeToAppend(
            CategoryName categoryName,
            Name name,
            Description description,
            Money money,
            Type type,
            ZonedDateTime dueDate
    ) {}
}
