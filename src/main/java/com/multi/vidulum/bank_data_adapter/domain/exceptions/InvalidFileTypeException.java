package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class InvalidFileTypeException extends BusinessException {

    private final String detectedType;

    public InvalidFileTypeException(String detectedType) {
        super(String.format("Invalid file type: [%s]. Expected CSV file", detectedType));
        this.detectedType = detectedType;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AI_ADAPTER_INVALID_FILE_TYPE;
    }
}
