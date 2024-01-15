package com.multi.vidulum.cashflow;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.UserId;

import java.util.HashMap;
import java.util.UUID;

class CashFlowFactory {
    CashFlow crete(String name, UserId userId, BankAccount bankAccount) {
        return new CashFlow(
                CashFlowId.of(UUID.randomUUID().toString()),
                name,
                bankAccount,
                userId,
                new HashMap<>(),
                new HashMap<>(),
                true

        );
    }
}
