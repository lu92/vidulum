package com.multi.vidulum.cashflow.domain;

/**
 * Origin of a category - indicates how the category was created.
 * <p>
 * Used for:
 * <ul>
 *   <li>Distinguishing system-provided categories from user-created ones</li>
 *   <li>Identifying categories imported from bank statements</li>
 *   <li>Applying different rules (e.g., system categories cannot be deleted)</li>
 * </ul>
 */
public enum CategoryOrigin {
    /**
     * System-provided default category (e.g., "Uncategorized").
     * Cannot be deleted or archived by the user.
     */
    SYSTEM,

    /**
     * Category imported from bank statement during historical import.
     * Created automatically during category mapping configuration.
     */
    IMPORTED,

    /**
     * Category created manually by the user.
     * Can be modified, archived, or deleted by the user.
     */
    USER_CREATED
}
