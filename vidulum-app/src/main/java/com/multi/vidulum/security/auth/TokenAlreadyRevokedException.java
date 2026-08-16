package com.multi.vidulum.security.auth;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

/**
 * Thrown when attempting to use a token that has already been revoked.
 * Results in HTTP 401 UNAUTHORIZED.
 */
@Getter
public class TokenAlreadyRevokedException extends BusinessException {

    private final String tokenId;

    public TokenAlreadyRevokedException(String tokenId) {
        super("Token has already been revoked");
        this.tokenId = tokenId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AUTH_TOKEN_REVOKED;
    }
}
