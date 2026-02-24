package com.multi.vidulum.security.auth;

/**
 * Thrown when a token is invalid (malformed, bad signature, cannot be parsed).
 * Results in HTTP 401 UNAUTHORIZED.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String reason) {
        super("Invalid token: %s".formatted(reason));
    }
}
