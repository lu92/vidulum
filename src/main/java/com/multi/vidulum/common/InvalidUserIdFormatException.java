package com.multi.vidulum.common;

import lombok.Getter;

/**
 * Exception thrown when a User ID does not match the expected format.
 * Valid format: UXXXXXXXX (U followed by 8 digits, e.g., U10000001)
 */
@Getter
public class InvalidUserIdFormatException extends RuntimeException {

    private final String providedId;

    public InvalidUserIdFormatException(String providedId) {
        super("Invalid User ID format: '" + providedId + "'. Expected: UXXXXXXXX (e.g., U10000001)");
        this.providedId = providedId;
    }
}
