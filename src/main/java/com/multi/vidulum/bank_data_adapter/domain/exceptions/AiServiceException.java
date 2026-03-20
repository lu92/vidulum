package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class AiServiceException extends RuntimeException {

    private final String aiErrorCode;
    private final String aiErrorMessage;
    private final int retryCount;

    public AiServiceException(String aiErrorCode, String aiErrorMessage, int retryCount) {
        super(String.format("AI service error [%s]: %s (after %d retries)",
            aiErrorCode, aiErrorMessage, retryCount));
        this.aiErrorCode = aiErrorCode;
        this.aiErrorMessage = aiErrorMessage;
        this.retryCount = retryCount;
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
        this.aiErrorCode = "UNKNOWN";
        this.aiErrorMessage = message;
        this.retryCount = 0;
    }
}
