package com.multi.vidulum.security.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response returned after successful logout.
 */
public record LogoutResponse(
        @JsonProperty("message")
        String message,

        @JsonProperty("user_id")
        String userId
) {
    public static LogoutResponse success(String userId) {
        return new LogoutResponse("Successfully logged out", userId);
    }
}
