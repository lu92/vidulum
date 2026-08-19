package com.multi.vidulum;

import com.multi.vidulum.common.auth.AuthenticatableUser;
import com.multi.vidulum.common.auth.AuthenticatableUserRepository;
import com.multi.vidulum.security.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only in-memory implementation of {@link AuthenticatableUserRepository}
 * for cashflow module integration tests. Replaces DomainUserRepository from vidulum-app.
 */
@Repository
public class InMemoryAuthenticatableUserRepository implements AuthenticatableUserRepository {

    private final Map<String, TestUser> usersByUsername = new ConcurrentHashMap<>();
    private final Map<String, TestUser> usersByEmail = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    public TestUser saveUser(String username, String password, String email) {
        String userId = "U" + String.format("%08d", idCounter.getAndIncrement());
        TestUser user = new TestUser(userId, username, password, email);
        usersByUsername.put(username, user);
        usersByEmail.put(email, user);
        return user;
    }

    @Override
    public Optional<TestUser> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    @Override
    public boolean existsByEmail(String email) {
        return usersByEmail.containsKey(email);
    }

    public record TestUser(
            String userId,
            String username,
            String password,
            String email
    ) implements AuthenticatableUser {

        @Override
        public String getAuthUserId() {
            return userId;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public String getEmail() {
            return email;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return Role.USER.getAuthorities();
        }
    }
}
