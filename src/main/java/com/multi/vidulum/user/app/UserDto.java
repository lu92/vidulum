package com.multi.vidulum.user.app;

import lombok.Builder;
import lombok.Data;

public class UserDto {

    @Data
    @Builder
    public static class CreateUserJson {
        private String username;
        private String password;
        private String email;
    }

    @Data
    @Builder
    public static class UserSummaryJson {
        private String userId;
        private String username;
        private String email;
        private boolean isActive;
    }
}
