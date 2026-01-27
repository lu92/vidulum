package com.multi.vidulum.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String code,
        String message,
        Instant timestamp,
        List<FieldError> fieldErrors
) {
    public static ApiError of(ErrorCode errorCode) {
        return new ApiError(
                errorCode.getHttpStatus().value(),
                errorCode.name(),
                errorCode.getDefaultMessage(),
                Instant.now(),
                null
        );
    }

    public static ApiError of(ErrorCode errorCode, String customMessage) {
        return new ApiError(
                errorCode.getHttpStatus().value(),
                errorCode.name(),
                customMessage,
                Instant.now(),
                null
        );
    }

    public static ApiError withFieldErrors(ErrorCode errorCode, List<FieldError> fieldErrors) {
        return new ApiError(
                errorCode.getHttpStatus().value(),
                errorCode.name(),
                errorCode.getDefaultMessage(),
                Instant.now(),
                fieldErrors
        );
    }

    public HttpStatus httpStatus() {
        return HttpStatus.valueOf(status);
    }
}
