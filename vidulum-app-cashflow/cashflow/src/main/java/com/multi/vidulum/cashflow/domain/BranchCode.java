package com.multi.vidulum.cashflow.domain;

/**
 * Branch identifier (optional, country-specific).
 * Identifies a specific branch of a bank.
 *
 * <p>Usage varies by country:
 * <ul>
 *   <li>Poland: Not used (null)</li>
 *   <li>Germany: May be included in IBAN structure</li>
 *   <li>UK: 6 digits (part of Sort Code)</li>
 *   <li>France: 5 digits</li>
 * </ul>
 */
public record BranchCode(String code) {
    public BranchCode {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Branch code cannot be null or blank");
        }
    }
}
