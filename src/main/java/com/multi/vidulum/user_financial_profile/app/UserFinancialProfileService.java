package com.multi.vidulum.user_financial_profile.app;

import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.BankName;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.user_financial_profile.domain.AccountSource;
import com.multi.vidulum.user_financial_profile.domain.AccountStatus;
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
import java.util.List;

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

    public void onCashFlowCreated(UserId userId, BankAccount bankAccount, CashFlowId cashFlowId) {
        String iban = bankAccount.bankAccountNumber().fetchRawIban();
        UserFinancialProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new UserFinancialProfileNotFoundException(userId));

        if (profile.ownsAccount(iban)) {
            log.debug("CashFlow IBAN [{}] already in profile of user [{}] - skipping auto-add",
                    iban, userId.getId());
            return;
        }

        String bankName = bankAccount.bankName() != null
                ? bankAccount.bankName().name()
                : "Unknown bank";
        String label = bankName;
        addAccount(
                userId,
                iban,
                bankAccount.bankAccountNumber().denomination().getId(),
                bankName,
                label,
                AccountSource.CASHFLOW,
                cashFlowId
        );
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
