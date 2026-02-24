package com.multi.vidulum.security.auth;

import lombok.Getter;

/**
 * Thrown when a token is not found in the database.
 * Results in HTTP 404 NOT_FOUND.
 */
@Getter
public class TokenNotFoundException extends RuntimeException {

    private final String tokenPrefix;

    public TokenNotFoundException(String token) {
        super("Token not found in database");
        this.tokenPrefix = token != null && token.length() > 20
                ? token.substring(0, 20) + "..."
                : "unknown";
    }
}
