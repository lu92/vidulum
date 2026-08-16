package com.multi.vidulum.common.auth;

import java.util.Optional;

/**
 * Repository interface for user authentication lookups.
 * Implemented by DomainUserRepository in the user module.
 */
public interface AuthenticatableUserRepository {
    Optional<? extends AuthenticatableUser> findByUsername(String username);
    boolean existsByEmail(String email);
}
