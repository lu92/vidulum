package com.multi.vidulum.security.config;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatableUserRepository;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import lombok.AllArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class SecurityContextUserProvider implements AuthenticatedUserProvider {

    private final AuthenticatableUserRepository userRepository;

    @Override
    public UserId getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .map(user -> UserId.of(user.getAuthUserId()))
                .orElseThrow(() -> new IllegalStateException("User not found: " + username));
    }
}
