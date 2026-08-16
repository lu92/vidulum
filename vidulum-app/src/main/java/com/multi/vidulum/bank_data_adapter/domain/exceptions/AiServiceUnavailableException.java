package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class AiServiceUnavailableException extends BusinessException {

    public AiServiceUnavailableException(String message) {
        super(message);
    }

    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AI_ADAPTER_AI_SERVICE_UNAVAILABLE;
    }
}
