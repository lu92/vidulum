package com.multi.vidulum;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.UserId;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper class for generating test IDs in unit and integration tests.
 * Provides sequential, human-readable IDs that match the production format.
 *
 * <p>ID formats:
 * <ul>
 *   <li>UserId: U + 8 digits (e.g., U10000001)</li>
 *   <li>CashFlowId: CF + 8 digits (e.g., CF10000001)</li>
 *   <li>CashChangeId: CC + 10 digits (e.g., CC1000000001)</li>
 * </ul>
 *
 * <p>Usage:
 * <ul>
 *   <li>Use {@link #nextUserId()}, {@link #nextCashFlowId()}, {@link #nextCashChangeId()} to generate unique test IDs</li>
 *   <li>Use {@link #reset()} in @BeforeEach if tests need predictable IDs</li>
 *   <li>Use invalidXxx() methods for testing validation errors</li>
 *   <li>Use nonExistentXxx() methods for testing "not found" scenarios</li>
 * </ul>
 */
public final class TestIds {

    private static final AtomicLong userCounter = new AtomicLong(10000000);
    private static final AtomicLong cashFlowCounter = new AtomicLong(10000000);
    private static final AtomicLong cashChangeCounter = new AtomicLong(1000000000);

    private TestIds() {
        // Utility class - no instantiation
    }

    // ============ UserId methods ============

    /**
     * Generates a new unique UserId for tests.
     * Thread-safe, IDs are sequential (U10000001, U10000002, ...).
     *
     * @return new UserId with valid format
     */
    public static UserId nextUserId() {
        return UserId.of(String.format("U%08d", userCounter.incrementAndGet()));
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
        return UserId.of("U99999999");
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
        return UserId.of(String.format("U%08d", suffix));
    }

    // ============ CashFlowId methods ============

    /**
     * Generates a new unique CashFlowId for tests.
     * Thread-safe, IDs are sequential (CF10000001, CF10000002, ...).
     *
     * @return new CashFlowId with valid format
     */
    public static CashFlowId nextCashFlowId() {
        return CashFlowId.of(String.format("CF%08d", cashFlowCounter.incrementAndGet()));
    }

    /**
     * Returns an invalid CashFlow ID string for testing validation errors.
     * This will cause InvalidCashFlowIdFormatException when passed to CashFlowId.of().
     *
     * @return invalid CashFlow ID string
     */
    public static String invalidCashFlowId() {
        return "invalid-cashflow-id";
    }

    /**
     * Returns a valid but non-existent CashFlow ID for testing "not found" scenarios.
     *
     * @return valid format CashFlowId that doesn't exist in the database
     */
    public static String nonExistentCashFlowId() {
        return "CF99999999";
    }

    /**
     * Returns a valid CashFlowId object that doesn't exist in the database.
     * Useful for testing "not found" scenarios.
     *
     * @return valid CashFlowId that doesn't exist
     */
    public static CashFlowId nonExistentCashFlowIdObject() {
        return CashFlowId.of("CF99999999");
    }

    /**
     * Creates a CashFlowId with a specific suffix for debugging.
     *
     * @param suffix a number between 1 and 99999999
     * @return CashFlowId with that specific number
     */
    public static CashFlowId cashFlowIdWithSuffix(long suffix) {
        if (suffix < 1 || suffix > 99999999) {
            throw new IllegalArgumentException("Suffix must be between 1 and 99999999");
        }
        return CashFlowId.of(String.format("CF%08d", suffix));
    }

    // ============ CashChangeId methods ============

    /**
     * Generates a new unique CashChangeId for tests.
     * Thread-safe, IDs are sequential (CC1000000001, CC1000000002, ...).
     *
     * @return new CashChangeId with valid format
     */
    public static CashChangeId nextCashChangeId() {
        return CashChangeId.of(String.format("CC%010d", cashChangeCounter.incrementAndGet()));
    }

    /**
     * Returns an invalid CashChange ID string for testing validation errors.
     * This will cause InvalidCashChangeIdFormatException when passed to CashChangeId.of().
     *
     * @return invalid CashChange ID string
     */
    public static String invalidCashChangeId() {
        return "invalid-cashchange-id";
    }

    /**
     * Returns a valid but non-existent CashChange ID for testing "not found" scenarios.
     *
     * @return valid format CashChangeId that doesn't exist in the database
     */
    public static String nonExistentCashChangeId() {
        return "CC9999999999";
    }

    /**
     * Returns a valid CashChangeId object that doesn't exist in the database.
     * Useful for testing "not found" scenarios.
     *
     * @return valid CashChangeId that doesn't exist
     */
    public static CashChangeId nonExistentCashChangeIdObject() {
        return CashChangeId.of("CC9999999999");
    }

    /**
     * Creates a CashChangeId with a specific suffix for debugging.
     *
     * @param suffix a number between 1 and 9999999999
     * @return CashChangeId with that specific number
     */
    public static CashChangeId cashChangeIdWithSuffix(long suffix) {
        if (suffix < 1 || suffix > 9999999999L) {
            throw new IllegalArgumentException("Suffix must be between 1 and 9999999999");
        }
        return CashChangeId.of(String.format("CC%010d", suffix));
    }

    // ============ Reset ============

    /**
     * Resets all counters to initial values.
     * Call this in @BeforeEach if your tests need predictable IDs.
     */
    public static void reset() {
        userCounter.set(10000000);
        cashFlowCounter.set(10000000);
        cashChangeCounter.set(1000000000);
    }
}
