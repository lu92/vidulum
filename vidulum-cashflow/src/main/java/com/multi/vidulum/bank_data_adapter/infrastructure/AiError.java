package com.multi.vidulum.bank_data_adapter.infrastructure;

/**
 * Represents an error from AI transformation.
 */
public record AiError(
    AiErrorCode code,
    String message
) {}
