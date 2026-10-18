package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class InvalidTransformationIdFormatException extends BusinessException {

    private final String providedId;

    public InvalidTransformationIdFormatException(String providedId) {
        super(String.format("Invalid transformation ID format: [%s]. Expected UUID format", providedId));
        this.providedId = providedId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AI_ADAPTER_INVALID_TRANSFORMATION_ID;
    }
}
