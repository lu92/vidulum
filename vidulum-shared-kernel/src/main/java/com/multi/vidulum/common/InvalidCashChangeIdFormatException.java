package com.multi.vidulum.common;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class InvalidCashChangeIdFormatException extends BusinessException {

    private final String providedId;

    public InvalidCashChangeIdFormatException(String providedId) {
        super("Invalid CashChange ID format: '" + providedId + "'. Expected: CCXXXXXXXXXX (e.g., CC1000000001)");
        this.providedId = providedId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INVALID_CASHCHANGE_ID_FORMAT;
    }
}
