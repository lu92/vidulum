package com.multi.vidulum.cashflow.app.commands.reject;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.shared.cqrs.commands.Command;

public record RejectCashChangeCommand(
        CashFlowId cashFlowId,
        CashChangeId cashChangeId,
        Reason reason) implements Command {
}
