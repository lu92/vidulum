package com.multi.vidulum.user_financial_profile.app;

import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.BankName;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.user_financial_profile.domain.AccountSource;
import com.multi.vidulum.user_financial_profile.domain.AccountStatus;
import com.multi.vidulum.user_financial_profile.domain.BankAccountAlreadyOwnedException;
import com.multi.vidulum.user_financial_profile.domain.DomainUserFinancialProfileRepository;
import com.multi.vidulum.user_financial_profile.domain.OwnedBankAccount;
import com.multi.vidulum.user_financial_profile.domain.UserFinancialProfile;
import com.multi.vidulum.user_financial_profile.domain.UserFinancialProfileEvent;
import com.multi.vidulum.user_financial_profile.domain.UserFinancialProfileNotFoundException;
import com.multi.vidulum.user_financial_profile.infrastructure.UserFinancialProfileEventEmitter;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@AllArgsConstructor
public class UserFinancialProfileService {

    private final DomainUserFinancialProfileRepository repository;
    private final UserFinancialProfileEventEmitter eventEmitter;
    private final Clock clock;

    public UserFinancialProfile createEmptyProfile(UserId userId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        UserFinancialProfile profile = UserFinancialProfile.createEmpty(userId, now);
        UserFinancialProfile saved = repository.save(profile);
        eventEmitter.emit(new UserFinancialProfileEvent.UserFinancialProfileCreatedEvent(userId, now));
        log.info("User financial profile created for user [{}]", userId.getId());
        return saved;
    }

    public OwnedBankAccount addAccount(
            UserId userId,
            String iban,
            String currency,
            String bankName,
            String label,
            AccountSource source,
            CashFlowId linkedCashFlowId
    ) {
        UserFinancialProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new UserFinancialProfileNotFoundException(userId));

        ZonedDateTime now = ZonedDateTime.now(clock);
        OwnedBankAccount account = new OwnedBankAccount(
                BankAccountNumber.fromIban(iban, Currency.of(currency)),
                new BankName(bankName),
                label,
                AccountStatus.ACTIVE,
                source,
                linkedCashFlowId,
                now,
                null
        );
        profile.addAccount(account, now);
        repository.save(profile);

        eventEmitter.emit(new UserFinancialProfileEvent.OwnedBankAccountAddedEvent(
                userId, account.rawIban(), account.bankName(), source, linkedCashFlowId, now
        ));
        log.info("Bank account [{}] added to profile of user [{}] (source={})",
                account.rawIban(), userId.getId(), source);
        return account;
    }

    /**
     * Bulk add multiple accounts in one operation.
     * Validate-all-before-save: any invalid IBAN, any duplicate within batch, or any
     * collision with already-owned accounts aborts the whole batch (nothing persisted).
     * On success: profile saved once, one event emitted per added account.
     */
    public List<OwnedBankAccount> addAccounts(
            UserId userId,
            List<BulkAccountRequest> requests,
            AccountSource source
    ) {
        UserFinancialProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new UserFinancialProfileNotFoundException(userId));

        // Pre-validate: parse all IBANs (throws on invalid) + detect intra-batch duplicates
        Set<String> ibansInBatch = new HashSet<>();
        List<OwnedBankAccount> toAdd = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now(clock);

        for (BulkAccountRequest req : requests) {
            BankAccountNumber bankAccountNumber = BankAccountNumber.fromIban(req.iban(), Currency.of(req.currency()));
            String rawIban = bankAccountNumber.fetchRawIban();
            if (!ibansInBatch.add(rawIban)) {
                throw new BankAccountAlreadyOwnedException(userId, rawIban);
            }
            if (profile.containsIban(rawIban)) {
                throw new BankAccountAlreadyOwnedException(userId, rawIban);
            }
            toAdd.add(new OwnedBankAccount(
                    bankAccountNumber,
                    new BankName(req.bankName()),
                    req.label(),
                    AccountStatus.ACTIVE,
                    source,
                    null,
                    now,
                    null
            ));
        }

        // All validated → persist + emit
        for (OwnedBankAccount account : toAdd) {
            profile.addAccount(account, now);
        }
        repository.save(profile);

        for (OwnedBankAccount account : toAdd) {
            eventEmitter.emit(new UserFinancialProfileEvent.OwnedBankAccountAddedEvent(
                    userId, account.rawIban(), account.bankName(), source, null, now
            ));
        }
        log.info("Bulk-added [{}] accounts to profile of user [{}] (source={})",
                toAdd.size(), userId.getId(), source);
        return toAdd;
    }

    public record BulkAccountRequest(String iban, String currency, String bankName, String label) {}

    /**
     * Idempotent handler for "user created a CashFlow with bankAccount X". Called from
     * the Kafka listener on the cash_flow topic.
     * - If X.iban already in profile  → set linkedCashFlowId on existing entry
     * - If X.iban not in profile      → create new entry with source=CASHFLOW
     */
    public void claimOrLinkAccountForCashFlow(UserId userId, BankAccount bankAccount, CashFlowId cashFlowId) {
        String iban = bankAccount.bankAccountNumber().fetchRawIban();
        UserFinancialProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new UserFinancialProfileNotFoundException(userId));

        ZonedDateTime now = ZonedDateTime.now(clock);

        if (profile.ownsAccount(iban)) {
            profile.linkCashFlow(iban, cashFlowId, now);
            repository.save(profile);
            log.info("Linked existing owned account [{}] to CashFlow [{}] for user [{}]",
                    iban, cashFlowId.id(), userId.getId());
            return;
        }

        String bankName = bankAccount.bankName() != null
                ? bankAccount.bankName().name()
                : "Unknown bank";
        addAccount(
                userId,
                iban,
                bankAccount.bankAccountNumber().denomination().getId(),
                bankName,
                bankName,
                AccountSource.CASHFLOW,
                cashFlowId
        );
    }

    public List<OwnedBankAccount> listAccountsAvailableForCashFlow(UserId userId) {
        UserFinancialProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new UserFinancialProfileNotFoundException(userId));
        return profile.getOwnedAccounts().stream()
                .filter(OwnedBankAccount::isActive)
                .filter(a -> a.linkedCashFlowId() == null)
                .toList();
    }

    public void removeAccount(UserId userId, String iban) {
        UserFinancialProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new UserFinancialProfileNotFoundException(userId));

        ZonedDateTime now = ZonedDateTime.now(clock);
        profile.removeAccount(iban, now);
        repository.save(profile);

        eventEmitter.emit(new UserFinancialProfileEvent.OwnedBankAccountRemovedEvent(userId, iban, now));
        log.info("Bank account [{}] removed from profile of user [{}]", iban, userId.getId());
    }

    public List<OwnedBankAccount> listAccounts(UserId userId) {
        UserFinancialProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new UserFinancialProfileNotFoundException(userId));
        return profile.getOwnedAccounts();
    }

    public boolean ownsAccount(UserId userId, String iban) {
        return repository.findByUserId(userId)
                .map(p -> p.ownsAccount(iban))
                .orElse(false);
    }
}
