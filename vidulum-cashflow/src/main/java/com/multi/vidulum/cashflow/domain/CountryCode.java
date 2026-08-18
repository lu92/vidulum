package com.multi.vidulum.cashflow.domain;

/**
 * ISO 3166-1 alpha-2 country code (e.g., PL, DE, GB, US).
 * Two uppercase letters representing a country.
 *
 * <p>Examples:
 * <ul>
 *   <li>PL - Poland</li>
 *   <li>DE - Germany</li>
 *   <li>GB - United Kingdom</li>
 *   <li>US - United States</li>
 * </ul>
 */
public record CountryCode(String code) {
    public CountryCode {
        if (code == null || !code.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("Invalid country code: " + code + ". Must be 2 uppercase letters (ISO 3166-1 alpha-2)");
        }
    }
}
