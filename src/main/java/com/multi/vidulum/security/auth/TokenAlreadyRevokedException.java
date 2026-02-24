package com.multi.vidulum.security.auth;

import lombok.Getter;

/**
 * Thrown when attempting to use a token that has already been revoked.
 * Results in HTTP 401 UNAUTHORIZED.
 */
@Getter
public class TokenAlreadyRevokedException extends RuntimeException {

    private final String tokenId;

    public TokenAlreadyRevokedException(String tokenId) {
        super("Token has already been revoked");
        this.tokenId = tokenId;
    }
}
