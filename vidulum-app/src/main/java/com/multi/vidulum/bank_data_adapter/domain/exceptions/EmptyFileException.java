package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class EmptyFileException extends BusinessException {

    public EmptyFileException() {
        super("Uploaded file is empty");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AI_ADAPTER_EMPTY_FILE;
    }
}
