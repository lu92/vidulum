package com.multi.vidulum.cashflow.domain;

import lombok.Getter;

/**
 * Exception thrown when a CashChange ID does not match the expected format.
 * Valid format: CCXXXXXXXXXX (CC followed by 10 digits, e.g., CC1000000001)
 */
@Getter
public class InvalidCashChangeIdFormatException extends RuntimeException {

    private final String providedId;

    public InvalidCashChangeIdFormatException(String providedId) {
        super("Invalid CashChange ID format: '" + providedId + "'. Expected: CCXXXXXXXXXX (e.g., CC1000000001)");
        this.providedId = providedId;
    }
}
