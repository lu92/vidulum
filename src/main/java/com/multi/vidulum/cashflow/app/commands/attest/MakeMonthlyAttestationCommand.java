package com.multi.vidulum.cashflow.app.commands.attest;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.YearMonth;
import java.time.ZonedDateTime;

public record MakeMonthlyAttestationCommand(
        CashFlowId cashFlowId,
        YearMonth period,
        Money currentMoney,
        ZonedDateTime dateTime
) implements Command {
}
