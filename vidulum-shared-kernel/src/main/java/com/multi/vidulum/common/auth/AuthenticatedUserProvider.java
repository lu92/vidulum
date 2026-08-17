package com.multi.vidulum.common.auth;

import com.multi.vidulum.common.UserId;

/**
 * Provides the currently authenticated user's identity.
 * Implemented by the security module (vidulum-app), consumed by domain modules.
 */
public interface AuthenticatedUserProvider {
    UserId getCurrentUserId();
}
