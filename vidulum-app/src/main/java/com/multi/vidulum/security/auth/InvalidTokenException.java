package com.multi.vidulum.security.auth;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Thrown when a token is invalid (malformed, bad signature, cannot be parsed).
 * Results in HTTP 401 UNAUTHORIZED.
 */
public class InvalidTokenException extends BusinessException {

    public InvalidTokenException(String reason) {
        super("Invalid token: %s".formatted(reason));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AUTH_TOKEN_INVALID;
    }
}
