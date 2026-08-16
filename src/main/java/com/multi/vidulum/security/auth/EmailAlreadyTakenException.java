package com.multi.vidulum.security.auth;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class EmailAlreadyTakenException extends BusinessException {

    public EmailAlreadyTakenException(String email) {
        super("Email '%s' is already registered".formatted(email));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AUTH_EMAIL_TAKEN;
    }
}
