package com.multi.vidulum;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;

/**
 * Who the integration tests are acting as.
 *
 * <p>These tests call controllers as plain Java methods, so there is no request and no security
 * context for {@code SecurityContextUserProvider} to read — it would refuse every call. A settable
 * identity stands in, the same shape the component tests already use ({@code () -> caller}).
 *
 * <p>It is mutable on purpose: ownership is only testable by switching who is asking, and a test
 * that can never change caller can never show that the wrong one is refused.
 */
public class TestAuthenticatedUser implements AuthenticatedUserProvider {

    private volatile UserId currentUserId = UserId.of("U00000001");

    @Override
    public UserId getCurrentUserId() {
        return currentUserId;
    }

    public void actAs(UserId userId) {
        this.currentUserId = userId;
    }
}
