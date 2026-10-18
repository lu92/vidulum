package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class AiRateLimitExceededException extends BusinessException {

    private final int retryAfterSeconds;

    public AiRateLimitExceededException(int retryAfterSeconds) {
        super(String.format("AI API rate limit exceeded. Retry after %d seconds", retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AI_ADAPTER_RATE_LIMIT_EXCEEDED;
    }
}
