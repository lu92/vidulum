package com.multi.vidulum.user.app;

import com.multi.vidulum.common.UserId;

public class UserNotActiveException extends RuntimeException {
    public UserNotActiveException(UserId userId) {
        super(String.format("User [%s] is not active!", userId));
    }
}
