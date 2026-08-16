package com.multi.vidulum.security.auth;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Thrown when a refresh token has expired (JWT expiration claim).
 * Results in HTTP 401 UNAUTHORIZED.
 */
public class RefreshTokenExpiredException extends BusinessException {

    public RefreshTokenExpiredException() {
        super("Refresh token has expired. Please login again.");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AUTH_REFRESH_TOKEN_EXPIRED;
    }
}
