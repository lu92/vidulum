package com.multi.vidulum.bank_data_adapter.domain;

/**
 * Result of CSV format detection.
 * Used to inform UI about how the file was processed.
 */
public enum DetectionResult {
    /**
     * CSV is in Vidulum canonical format (matching BankCsvRow headers).
     * No AI processing needed - instant processing (~50ms).
     */
    CANONICAL,

    /**
     * Bank format was recognized from cache (previous AI transformation).
     * Uses cached mapping rules - instant processing (~100ms).
     */
    CACHED,

    /**
     * New bank format - AI was used to analyze and transform.
     * Processing time: 5-15 seconds.
     */
    AI_TRANSFORMED
}
