package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Currency;

/**
 * Bank account number with IBAN validation.
 * Uses IbanNumber for validation and data extraction.
 *
 * <p>Example usage:
 * <pre>
 * BankAccountNumber accountNumber = BankAccountNumber.fromIban(
 *     "PL61109010140000071219812874",
 *     Currency.of("PLN")
 * );
 * </pre>
 */
public record BankAccountNumber(IbanNumber iban, Currency denomination) {

    /**
     * Factory method from raw IBAN string.
     * Validates the IBAN during construction.
     *
     * @param ibanString IBAN string (spaces allowed, will be normalized)
     * @param denomination Currency (e.g., PLN, EUR, USD)
     * @return validated BankAccountNumber
     * @throws InvalidBankAccountNumberException if IBAN is invalid
     */
    public static BankAccountNumber fromIban(String ibanString, Currency denomination) {
        return new BankAccountNumber(new IbanNumber(ibanString), denomination);
    }

    /**
     * Fetch formatted IBAN string (with spaces)
     * Example: "PL 61 1090 1014 0000 0712 1981 2874"
     */
    public String fetchFormattedIban() {
        return iban.toFormattedString();
    }

    /**
     * Fetch raw IBAN string (without spaces)
     * Example: "PL61109010140000071219812874"
     */
    public String fetchRawIban() {
        return iban.value();
    }
}
