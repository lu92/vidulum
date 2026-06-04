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
import java.util.Objects;
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

    /**
     * Idempotent — if existing entry already has the same linkedCashFlowId, no-op.
     * Otherwise replaces the entry with a copy carrying the new linkedCashFlowId.
     */
    public void linkCashFlow(String iban, CashFlowId cashFlowId, ZonedDateTime now) {
        OwnedBankAccount existing = findByIban(iban)
                .orElseThrow(() -> new OwnedAccountNotFoundException(userId, iban));
        if (Objects.equals(existing.linkedCashFlowId(), cashFlowId)) {
            return;
        }
        OwnedBankAccount updated = new OwnedBankAccount(
                existing.bankAccountNumber(),
                existing.bankName(),
                existing.label(),
                existing.status(),
                existing.source(),
                cashFlowId,
                existing.addedAt(),
                existing.closedAt()
        );
        ownedAccounts.remove(existing);
        ownedAccounts.add(updated);
        this.lastModified = now;
    }

    public void removeAccount(String iban, ZonedDateTime now) {
        OwnedBankAccount existing = findByIban(iban)
                .orElseThrow(() -> new OwnedAccountNotFoundException(userId, iban));
        // Protection applies to any account currently linked to a CashFlow, regardless of how
        // the account entered the registry (CASHFLOW, MANUAL+linked-later, etc.).
        if (existing.linkedCashFlowId() != null && existing.isActive()) {
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
