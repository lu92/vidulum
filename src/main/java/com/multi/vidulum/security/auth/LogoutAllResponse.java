package com.multi.vidulum.security.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response returned after logging out from all devices.
 */
public record LogoutAllResponse(
        @JsonProperty("message")
        String message,

        @JsonProperty("user_id")
        String userId,

        @JsonProperty("revoked_sessions_count")
        int revokedSessionsCount
) {
    public static LogoutAllResponse success(String userId, int revokedCount) {
        return new LogoutAllResponse(
                "Successfully logged out from all devices",
                userId,
                revokedCount
        );
    }
}
