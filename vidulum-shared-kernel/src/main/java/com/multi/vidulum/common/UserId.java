package com.multi.vidulum.common;

import lombok.*;

import java.util.regex.Pattern;

/**
 * Value object representing a User ID.
 * Format: U + 8 digits (e.g., U10000001)
 *
 * Use {@link BusinessIdGenerator#generateUserId()} to create new IDs.
 * Use {@link UserId#of(String)} to parse existing IDs (validates format).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserId {

    private static final Pattern PATTERN = Pattern.compile("U\\d{8}");

    String id;

    /**
     * Creates a UserId from a string, validating the format.
     *
     * @param id the user ID string (must match pattern UXXXXXXXX)
     * @return validated UserId
     * @throws InvalidUserIdFormatException if the format is invalid
     */
    public static UserId of(String id) {
        if (id == null || !PATTERN.matcher(id).matches()) {
            throw new InvalidUserIdFormatException(id);
        }
        return new UserId(id);
    }
}
