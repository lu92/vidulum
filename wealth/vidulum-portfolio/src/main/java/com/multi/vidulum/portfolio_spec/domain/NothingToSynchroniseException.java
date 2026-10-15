package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * The snapshot matches what we already know, so there is nothing to specify.
 *
 * <p>Creating an empty spec would be worse than refusing: it would show up as a pending decision
 * the user cannot act on, and every idle synchronisation would leave one behind (§4.5, D8).
 */
public class NothingToSynchroniseException extends BusinessException {

    public NothingToSynchroniseException(UserId userId) {
        super("Snapshot matches the known state for user [" + userId.getId()
                + "] - no specification created");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_NOTHING_TO_SYNCHRONISE;
    }
}
