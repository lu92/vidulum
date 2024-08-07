package com.multi.vidulum.user.app;

import com.multi.vidulum.common.UserId;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(UserId userId) {
        super(String.format("User [%s] not found!", userId));
    }

    public UserNotFoundException(String email) {
        super(String.format("User with username [%s] not found!", email));
    }
}
