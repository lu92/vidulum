package com.multi.vidulum.bank_data_adapter.infrastructure;

/**
 * Error codes returned by AI during CSV transformation.
 */
public enum AiErrorCode {
    UNRECOGNIZED_FORMAT,      // Could not recognize CSV structure
    MISSING_REQUIRED_COLUMN,  // Required column not found
    DATE_PARSE_ERROR,         // Problem parsing dates
    EMPTY_FILE,               // Empty file
    INVALID_RESPONSE,         // AI response is not valid CSV
    EMPTY_RESPONSE,           // AI returned empty response
    AI_SERVICE_ERROR,         // Error from Claude API
    RATE_LIMIT_EXCEEDED       // API rate limit exceeded
}
