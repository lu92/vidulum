package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Source of a pattern mapping.
 * Used to track where a categorization suggestion came from.
 */
public enum PatternSource {
    /**
     * Global cache - known patterns like BIEDRONKA, ZUS, NETFLIX.
     * Seeded at application startup. FREE to use.
     */
    GLOBAL,

    /**
     * User cache - patterns learned from user's previous categorizations.
     * Stored per-user. FREE to use.
     */
    USER,

    /**
     * AI suggestion - patterns categorized by AI during import.
     * Costs money but provides intelligent categorization.
     */
    AI
}
