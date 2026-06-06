package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.cashflow.domain.BankName;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.UserId;

import java.time.ZonedDateTime;

public sealed interface UserFinancialProfileEvent
        permits
        UserFinancialProfileEvent.UserFinancialProfileCreatedEvent,
        UserFinancialProfileEvent.OwnedBankAccountAddedEvent,
        UserFinancialProfileEvent.OwnedBankAccountClosedEvent,
        UserFinancialProfileEvent.OwnedBankAccountReactivatedEvent,
        UserFinancialProfileEvent.OwnedBankAccountRemovedEvent {

    UserId userId();

    ZonedDateTime occurredAt();

    record UserFinancialProfileCreatedEvent(
            UserId userId,
            ZonedDateTime occurredAt
    ) implements UserFinancialProfileEvent {
    }

    record OwnedBankAccountAddedEvent(
            UserId userId,
            String iban,
            BankName bankName,
            AccountSource source,
            CashFlowId linkedCashFlowId,
            ZonedDateTime occurredAt
    ) implements UserFinancialProfileEvent {
    }

    record OwnedBankAccountClosedEvent(
            UserId userId,
            String iban,
            ZonedDateTime occurredAt
    ) implements UserFinancialProfileEvent {
    }

    record OwnedBankAccountReactivatedEvent(
            UserId userId,
            String iban,
            ZonedDateTime occurredAt
    ) implements UserFinancialProfileEvent {
    }

    record OwnedBankAccountRemovedEvent(
            UserId userId,
            String iban,
            ZonedDateTime occurredAt
    ) implements UserFinancialProfileEvent {
    }
}
