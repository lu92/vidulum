package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.UserId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFinancialProfile {

    private UserId userId;
    private List<OwnedBankAccount> ownedAccounts;
    private ZonedDateTime created;
    private ZonedDateTime lastModified;

    public static UserFinancialProfile createEmpty(UserId userId, ZonedDateTime now) {
        return UserFinancialProfile.builder()
                .userId(userId)
                .ownedAccounts(new ArrayList<>())
                .created(now)
                .lastModified(now)
                .build();
    }

    public void addAccount(OwnedBankAccount account, ZonedDateTime now) {
        if (containsIban(account.rawIban())) {
            throw new BankAccountAlreadyOwnedException(userId, account.rawIban());
        }
        ownedAccounts.add(account);
        this.lastModified = now;
    }

    public void removeAccount(String iban, ZonedDateTime now) {
        OwnedBankAccount existing = findByIban(iban)
                .orElseThrow(() -> new OwnedAccountNotFoundException(userId, iban));
        if (existing.source() == AccountSource.CASHFLOW
                && existing.linkedCashFlowId() != null
                && existing.isActive()) {
            throw new CannotRemoveLinkedCashFlowAccountException(userId, iban, existing.linkedCashFlowId());
        }
        ownedAccounts.remove(existing);
        this.lastModified = now;
    }

    public boolean ownsAccount(String iban) {
        return findByIban(iban).isPresent();
    }

    public Optional<OwnedBankAccount> findByIban(String iban) {
        return ownedAccounts.stream().filter(a -> a.matches(iban)).findFirst();
    }

    public boolean containsIban(String iban) {
        return findByIban(iban).isPresent();
    }
}
