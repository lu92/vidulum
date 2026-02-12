package com.multi.vidulum.cashflow.domain;

import lombok.Getter;

/**
 * Exception thrown when a CashFlow ID does not match the expected format.
 * Valid format: CFXXXXXXXX (CF followed by 8 digits, e.g., CF10000001)
 */
@Getter
public class InvalidCashFlowIdFormatException extends RuntimeException {

    private final String providedId;

    public InvalidCashFlowIdFormatException(String providedId) {
        super("Invalid CashFlow ID format: '" + providedId + "'. Expected: CFXXXXXXXX (e.g., CF10000001)");
        this.providedId = providedId;
    }
}
