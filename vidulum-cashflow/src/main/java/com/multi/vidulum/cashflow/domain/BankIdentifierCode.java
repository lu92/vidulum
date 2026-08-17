package com.multi.vidulum.cashflow.domain;

import org.iban4j.Bic;
import org.iban4j.BicFormatException;
import org.iban4j.BicUtil;

/**
 * BIC/SWIFT code value object (ISO 9362).
 * Bank Identifier Code used for international wire transfers.
 *
 * <p>Format: 8 or 11 characters
 * <ul>
 *   <li>8 characters: Institution code (e.g., DEUTDEFF - Deutsche Bank Frankfurt)</li>
 *   <li>11 characters: Institution + branch code (e.g., DEUTDEFF500 - Deutsche Bank Frankfurt branch 500)</li>
 * </ul>
 *
 * <p>Structure:
 * <pre>
 * DEUT DE FF 500
 * │    │  │  │
 * │    │  │  └─ Branch Code (optional, 3 chars)
 * │    │  └──── Location Code (2 chars)
 * │    └─────── Country Code (2 chars)
 * └──────────── Bank Code (4 chars)
 * </pre>
 *
 * <p>Examples:
 * <ul>
 *   <li>DEUTDEFF - Deutsche Bank Frankfurt (main office)</li>
 *   <li>BPKOPLPW - PKO BP Warsaw</li>
 *   <li>INGBPLPW - ING Bank Śląski Warsaw</li>
 * </ul>
 */
public record BankIdentifierCode(String value) {

    public BankIdentifierCode {
        if (value == null || value.isBlank()) {
            throw new InvalidBankAccountNumberException(
                "BIC/SWIFT code cannot be null or blank",
                value,
                InvalidBankAccountNumberException.AccountType.SWIFT_BIC
            );
        }

        String normalized = value.replaceAll("\\s+", "").toUpperCase();

        try {
            BicUtil.validate(normalized);
        } catch (BicFormatException e) {
            throw new InvalidBankAccountNumberException(
                "Invalid BIC/SWIFT format: " + e.getMessage(),
                value,
                InvalidBankAccountNumberException.AccountType.SWIFT_BIC
            );
        }

        value = normalized;
    }

    /**
     * Extract bank code (first 4 characters)
     * Example: "DEUT" from "DEUTDEFF500"
     */
    public String extractBankCode() {
        Bic bic = Bic.valueOf(value);
        return bic.getBankCode();
    }

    /**
     * Extract country code (characters 5-6)
     * Example: "DE" from "DEUTDEFF500"
     */
    public CountryCode extractCountryCode() {
        Bic bic = Bic.valueOf(value);
        return new CountryCode(bic.getCountryCode().toString());
    }

    /**
     * Extract location code (characters 7-8)
     * Example: "FF" from "DEUTDEFF500"
     */
    public String extractLocationCode() {
        Bic bic = Bic.valueOf(value);
        return bic.getLocationCode();
    }

    /**
     * Extract branch code (characters 9-11, if present)
     * Example: "500" from "DEUTDEFF500"
     * Returns null for 8-character BICs
     */
    public String extractBranchCode() {
        Bic bic = Bic.valueOf(value);
        return bic.getBranchCode();
    }
}
