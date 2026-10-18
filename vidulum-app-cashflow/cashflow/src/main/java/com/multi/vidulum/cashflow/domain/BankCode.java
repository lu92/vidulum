package com.multi.vidulum.cashflow.domain;

/**
 * Bank identifier code (country-specific format).
 * Identifies a specific bank within a country.
 *
 * <p>Format varies by country:
 * <ul>
 *   <li>Poland: 8 digits (e.g., "10901014" - Santander Bank Polska)</li>
 *   <li>Germany: 8 digits (Bankleitzahl, e.g., "37040044" - Commerzbank)</li>
 *   <li>UK: 6 digits (Sort Code, e.g., "200415" - Barclays)</li>
 * </ul>
 */
public record BankCode(String code) {
    public BankCode {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Bank code cannot be null or blank");
        }
    }
}
