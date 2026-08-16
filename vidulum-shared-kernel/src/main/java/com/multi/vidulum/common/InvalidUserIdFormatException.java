package com.multi.vidulum.common;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

/**
 * Exception thrown when a User ID does not match the expected format.
 * Valid format: UXXXXXXXX (U followed by 8 digits, e.g., U10000001)
 */
@Getter
public class InvalidUserIdFormatException extends BusinessException {

    private final String providedId;

    public InvalidUserIdFormatException(String providedId) {
        super("Invalid User ID format: '" + providedId + "'. Expected: UXXXXXXXX (e.g., U10000001)");
        this.providedId = providedId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INVALID_USER_ID_FORMAT;
    }
}
