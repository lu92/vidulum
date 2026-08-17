package com.multi.vidulum.cashflow.domain;

import org.iban4j.Iban;
import org.iban4j.IbanFormatException;
import org.iban4j.IbanUtil;
import org.iban4j.InvalidCheckDigitException;
import org.iban4j.UnsupportedCountryException;

/**
 * IBAN value object with validation according to ISO 13616.
 * Immutable and always valid (throws exception on invalid input).
 *
 * <p>Example valid IBANs:
 * <ul>
 *   <li>Poland: PL61109010140000071219812874</li>
 *   <li>Germany: DE89370400440532013000</li>
 *   <li>UK: GB29NWBK60161331926819</li>
 * </ul>
 *
 * <p>The IBAN is normalized (spaces removed, uppercase) during construction.
 */
public record IbanNumber(String value) {

    public IbanNumber {
        if (value == null || value.isBlank()) {
            throw new InvalidBankAccountNumberException(
                "IBAN cannot be null or blank",
                value,
                InvalidBankAccountNumberException.AccountType.IBAN
            );
        }

        // Normalize: remove spaces and convert to uppercase
        String normalized = value.replaceAll("\\s+", "").toUpperCase();

        try {
            // Validate using iban4j
            IbanUtil.validate(normalized);
        } catch (IbanFormatException e) {
            throw new InvalidBankAccountNumberException(
                "Invalid IBAN format: " + e.getMessage(),
                value,
                InvalidBankAccountNumberException.AccountType.IBAN
            );
        } catch (InvalidCheckDigitException e) {
            throw new InvalidBankAccountNumberException(
                "Invalid IBAN check digits: " + e.getMessage(),
                value,
                InvalidBankAccountNumberException.AccountType.IBAN
            );
        } catch (UnsupportedCountryException e) {
            throw new InvalidBankAccountNumberException(
                "Unsupported IBAN country code: " + e.getMessage(),
                value,
                InvalidBankAccountNumberException.AccountType.IBAN
            );
        }

        // Store normalized value
        value = normalized;
    }

    /**
     * Extract country code from IBAN (e.g., "PL" from "PL61...")
     */
    public CountryCode extractCountryCode() {
        Iban iban = Iban.valueOf(value);
        return new CountryCode(iban.getCountryCode().toString());
    }

    /**
     * Extract bank code from IBAN (country-specific format).
     * For Poland: 8 digits (e.g., "10901014")
     * For Germany: 8 digits (Bankleitzahl)
     */
    public BankCode extractBankCode() {
        Iban iban = Iban.valueOf(value);
        String bankCodeValue = iban.getBankCode();
        return bankCodeValue != null ? new BankCode(bankCodeValue) : null;
    }

    /**
     * Extract branch code from IBAN (if country uses it).
     * Poland: null (not used)
     * Germany: may be included in bank code
     * UK: 6 digits (Sort Code)
     */
    public BranchCode extractBranchCode() {
        Iban iban = Iban.valueOf(value);
        String branchCodeValue = iban.getBranchCode();
        return branchCodeValue != null ? new BranchCode(branchCodeValue) : null;
    }

    /**
     * Extract account number portion from IBAN
     */
    public String extractAccountNumber() {
        Iban iban = Iban.valueOf(value);
        return iban.getAccountNumber();
    }

    /**
     * Get formatted IBAN (with spaces every 4 characters)
     * Example: "PL 61 1090 1014 0000 0712 1981 2874"
     */
    public String toFormattedString() {
        // Format IBAN with spaces every 4 characters
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                formatted.append(' ');
            }
            formatted.append(value.charAt(i));
        }
        return formatted.toString();
    }
}
