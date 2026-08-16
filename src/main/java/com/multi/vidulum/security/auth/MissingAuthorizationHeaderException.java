package com.multi.vidulum.security.auth;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Thrown when Authorization header is missing or doesn't start with "Bearer ".
 * Results in HTTP 400 BAD_REQUEST.
 */
public class MissingAuthorizationHeaderException extends BusinessException {

    public MissingAuthorizationHeaderException() {
        super("Authorization header is missing or invalid. Expected: Bearer <token>");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AUTH_MISSING_TOKEN;
    }
}
