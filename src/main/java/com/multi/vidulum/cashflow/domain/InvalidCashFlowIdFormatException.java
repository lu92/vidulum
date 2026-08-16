package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

/**
 * Exception thrown when a CashFlow ID does not match the expected format.
 * Valid format: CFXXXXXXXX (CF followed by 8 digits, e.g., CF10000001)
 */
@Getter
public class InvalidCashFlowIdFormatException extends BusinessException {

    private final String providedId;

    public InvalidCashFlowIdFormatException(String providedId) {
        super("Invalid CashFlow ID format: '" + providedId + "'. Expected: CFXXXXXXXX (e.g., CF10000001)");
        this.providedId = providedId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INVALID_CASHFLOW_ID_FORMAT;
    }
}
