package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class IngestionServiceException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;

    public IngestionServiceException(String message, int httpStatus, String errorCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public IngestionServiceException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 0;
        this.errorCode = null;
    }
}
