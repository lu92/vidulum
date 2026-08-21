package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.common.BankAccountId;

import java.util.Set;

/**
 * Snapshot of bank accounts owned by a user, loaded once per staging session.
 * Used for self-transfer detection during transaction staging.
 */
public record OwnedAccountRegistry(Set<BankAccountId> ownedAccounts) {

    public static final OwnedAccountRegistry EMPTY = new OwnedAccountRegistry(Set.of());

    public boolean isOwnedAccount(BankAccountId accountId) {
        return ownedAccounts.contains(accountId);
    }
}
