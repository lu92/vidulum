package com.multi.vidulum.common;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class UserNotFoundException extends BusinessException {
    public UserNotFoundException(UserId userId) {
        super(String.format("User [%s] not found!", userId));
    }

    public UserNotFoundException(String username) {
        super(String.format("User with username [%s] not found!", username));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.RESOURCE_NOT_FOUND;
    }
}
