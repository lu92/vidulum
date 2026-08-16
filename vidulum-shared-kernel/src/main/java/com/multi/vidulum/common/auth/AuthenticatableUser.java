package com.multi.vidulum.common.auth;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Interface for user identity in authentication context.
 * Implemented by User aggregate in the user module.
 */
public interface AuthenticatableUser {
    String getAuthUserId();
    String getUsername();
    String getPassword();
    String getEmail();
    Collection<? extends GrantedAuthority> getAuthorities();
}
