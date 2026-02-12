package com.multi.vidulum;

import com.multi.vidulum.common.UserId;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper class for generating test IDs in unit and integration tests.
 * Provides sequential, human-readable IDs that match the production format.
 *
 * Usage:
 * - Use {@link #nextUserId()} to generate unique test user IDs
 * - Use {@link #reset()} in @BeforeEach if tests need predictable IDs
 * - Use {@link #invalidUserId()} for testing validation errors
 * - Use {@link #nonExistentUserId()} for testing "not found" scenarios
 */
public final class TestIds {

    private static final AtomicLong userCounter = new AtomicLong(10000000);

    private TestIds() {
        // Utility class - no instantiation
    }

    /**
     * Generates a new unique UserId for tests.
     * Thread-safe, IDs are sequential (U10000001, U10000002, ...).
     *
     * @return new UserId with valid format
     */
    public static UserId nextUserId() {
        return new UserId(String.format("U%08d", userCounter.incrementAndGet()));
    }

    /**
     * Returns an invalid user ID string for testing validation errors.
     * This will cause InvalidUserIdFormatException when passed to UserId.of().
     *
     * @return invalid user ID string
     */
    public static String invalidUserId() {
        return "invalid-user-id";
    }

    /**
     * Returns a valid but non-existent user ID for testing "not found" scenarios.
     *
     * @return valid format userId that doesn't exist in the database
     */
    public static String nonExistentUserId() {
        return "U99999999";
    }

    /**
     * Returns a valid UserId object that doesn't exist in the database.
     * Useful for testing "not found" scenarios.
     *
     * @return valid UserId that doesn't exist
     */
    public static UserId nonExistentUserIdObject() {
        return new UserId("U99999999");
    }

    /**
     * Resets all counters to initial values.
     * Call this in @BeforeEach if your tests need predictable IDs.
     */
    public static void reset() {
        userCounter.set(10000000);
    }

    /**
     * Creates a UserId with a specific suffix for debugging.
     * Useful when you need to identify a specific user in logs.
     *
     * @param suffix a number between 1 and 99999999
     * @return UserId with that specific number
     */
    public static UserId userIdWithSuffix(long suffix) {
        if (suffix < 1 || suffix > 99999999) {
            throw new IllegalArgumentException("Suffix must be between 1 and 99999999");
        }
        return new UserId(String.format("U%08d", suffix));
    }
}
