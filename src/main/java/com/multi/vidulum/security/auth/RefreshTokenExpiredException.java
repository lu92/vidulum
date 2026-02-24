package com.multi.vidulum.security.auth;

/**
 * Thrown when a refresh token has expired (JWT expiration claim).
 * Results in HTTP 401 UNAUTHORIZED.
 */
public class RefreshTokenExpiredException extends RuntimeException {

    public RefreshTokenExpiredException() {
        super("Refresh token has expired. Please login again.");
    }
}
