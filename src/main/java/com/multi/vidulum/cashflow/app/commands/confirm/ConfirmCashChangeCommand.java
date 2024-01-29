package com.multi.vidulum.cashflow.app.commands.confirm;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

public record ConfirmCashChangeCommand(
        CashFlowId cashFlowId,
        CashChangeId cashChangeId,
        ZonedDateTime endDate) implements Command {
}
