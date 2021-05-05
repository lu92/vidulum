package com.multi.vidulum.user.app;

import com.multi.vidulum.common.UserId;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(UserId userId) {
        super(String.format("User [%s] not found!", userId));
    }
}
