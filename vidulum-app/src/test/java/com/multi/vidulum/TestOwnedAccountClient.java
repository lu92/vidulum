package com.multi.vidulum;

import com.multi.vidulum.bank_data_ingestion.app.OwnedAccountClient;
import com.multi.vidulum.bank_data_ingestion.app.OwnedAccountRegistry;
import com.multi.vidulum.common.BankAccountId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.user_financial_profile.app.UserFinancialProfileService;
import com.multi.vidulum.user_financial_profile.domain.OwnedBankAccount;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test implementation of OwnedAccountClient that delegates to UserFinancialProfileService
 * directly (no HTTP). Used in integration tests where both services run in the same Spring context.
 */
public class TestOwnedAccountClient implements OwnedAccountClient {

    private final UserFinancialProfileService userFinancialProfileService;

    public TestOwnedAccountClient(UserFinancialProfileService userFinancialProfileService) {
        this.userFinancialProfileService = userFinancialProfileService;
    }

    @Override
    public OwnedAccountRegistry loadForUser(UserId userId) {
        try {
            Set<BankAccountId> ownedIbans = userFinancialProfileService.listAccounts(userId).stream()
                    .map(OwnedBankAccount::rawIban)
                    .map(BankAccountId::new)
                    .collect(Collectors.toSet());
            return new OwnedAccountRegistry(ownedIbans);
        } catch (Exception e) {
            return OwnedAccountRegistry.EMPTY;
        }
    }
}
