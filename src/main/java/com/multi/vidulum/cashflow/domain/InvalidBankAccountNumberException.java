package com.multi.vidulum.cashflow.domain;

/**
 * Exception thrown when an invalid bank account number is provided.
 * This can occur when:
 * - IBAN format is invalid (wrong length, bad checksum)
 * - SWIFT/BIC format is invalid
 */
public class InvalidBankAccountNumberException extends RuntimeException {

    private final String invalidAccount;
    private final AccountType accountType;

    public enum AccountType {
        IBAN, SWIFT_BIC, UNKNOWN
    }

    public InvalidBankAccountNumberException(String message, String invalidAccount, AccountType accountType) {
        super(message);
        this.invalidAccount = invalidAccount;
        this.accountType = accountType;
    }

    public InvalidBankAccountNumberException(String message, String invalidAccount) {
        this(message, invalidAccount, AccountType.UNKNOWN);
    }

    public String getInvalidAccount() {
        return invalidAccount;
    }

    public AccountType getAccountType() {
        return accountType;
    }
}
