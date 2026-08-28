package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.common.BankAccountId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.user_financial_profile.api.OwnedAccountsListJson;
import com.multi.vidulum.user_financial_profile.api.UserFinancialProfileApi;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Snapshot of bank accounts owned by a user, loaded once per staging session.
 * Used for self-transfer detection during transaction staging.
 */
@Slf4j
public record OwnedAccountRegistry(Set<BankAccountId> ownedAccounts) {

    public static final OwnedAccountRegistry EMPTY = new OwnedAccountRegistry(Set.of());

    public boolean isOwnedAccount(BankAccountId accountId) {
        return ownedAccounts.contains(accountId);
    }

    public static OwnedAccountRegistry from(UserFinancialProfileApi api, UserId userId) {
        try {
            OwnedAccountsListJson response = api.list();
            if (response == null || response.accounts() == null) {
                log.info("No owned accounts found for user [{}]", userId.getId());
                return EMPTY;
            }
            Set<BankAccountId> ownedIbans = response.accounts().stream()
                    .map(a -> new BankAccountId(a.iban()))
                    .collect(Collectors.toSet());
            log.info("Loaded {} owned accounts for user [{}]", ownedIbans.size(), userId.getId());
            return new OwnedAccountRegistry(ownedIbans);
        } catch (Exception e) {
            log.warn("Failed to load owned accounts for user [{}]: {} ({})",
                    userId.getId(), e.getMessage(), e.getClass().getSimpleName());
            return EMPTY;
        }
    }
}
