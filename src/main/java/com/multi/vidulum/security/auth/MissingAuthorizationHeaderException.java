package com.multi.vidulum.security.auth;

/**
 * Thrown when Authorization header is missing or doesn't start with "Bearer ".
 * Results in HTTP 400 BAD_REQUEST.
 */
public class MissingAuthorizationHeaderException extends RuntimeException {

    public MissingAuthorizationHeaderException() {
        super("Authorization header is missing or invalid. Expected: Bearer <token>");
    }
}
