package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;

/**
 * Bank account aggregate containing IBAN-validated account details and extracted metadata.
 *
 * <p>The account number is validated as IBAN (ISO 13616), and additional data is extracted:
 * <ul>
 *   <li>Country code (e.g., PL, DE, GB)</li>
 *   <li>Bank code (country-specific format)</li>
 *   <li>Branch code (optional, country-specific)</li>
 *   <li>SWIFT/BIC code (optional, for international transfers)</li>
 * </ul>
 */
public record BankAccount(
    BankName bankName,
    BankAccountNumber bankAccountNumber,
    Money balance,
    CountryCode countryCode,
    BankCode bankCode,
    BranchCode branchCode,
    BankIdentifierCode swiftBic
) {

    /**
     * Factory method with automatic data extraction from IBAN.
     *
     * @param bankName Optional bank name (e.g., "PKO BP")
     * @param ibanString IBAN string (e.g., "PL61109010140000071219812874")
     * @param denomination Currency (e.g., PLN, EUR, USD)
     * @param initialBalance Initial account balance
     * @param swiftBic Optional SWIFT/BIC code (e.g., "BPKOPLPW")
     * @return BankAccount with validated IBAN and extracted metadata
     * @throws InvalidBankAccountNumberException if IBAN or BIC is invalid
     */
    public static BankAccount fromIban(
            String bankName,
            String ibanString,
            Currency denomination,
            Money initialBalance,
            String swiftBic
    ) {
        IbanNumber iban = new IbanNumber(ibanString);
        BankAccountNumber accountNumber = new BankAccountNumber(iban, denomination);

        return new BankAccount(
            bankName != null ? new BankName(bankName) : null,
            accountNumber,
            initialBalance,
            iban.extractCountryCode(),
            iban.extractBankCode(),
            iban.extractBranchCode(),  // Can be null
            swiftBic != null && !swiftBic.isBlank() ? new BankIdentifierCode(swiftBic) : null
        );
    }

    public BankAccount withUpdatedBalance(Money updatedBalance) {
        return new BankAccount(
            bankName,
            bankAccountNumber,
            updatedBalance,
            countryCode,
            bankCode,
            branchCode,
            swiftBic
        );
    }
}
