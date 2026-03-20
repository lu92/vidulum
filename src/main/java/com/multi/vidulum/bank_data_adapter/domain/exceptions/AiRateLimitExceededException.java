package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class AiRateLimitExceededException extends RuntimeException {

    private final int retryAfterSeconds;

    public AiRateLimitExceededException(int retryAfterSeconds) {
        super(String.format("AI API rate limit exceeded. Retry after %d seconds", retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
