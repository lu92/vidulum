package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * The exchange no longer says what it said when the specification was built.
 *
 * <p>Raised by {@code confirm}, which always compares against a fresh snapshot regardless of the
 * specification's age — time is only an approximation of the question we actually care about
 * (§4.5). A snapshot two hours old on an idle account is current; one thirty seconds old on an
 * actively traded account is not.
 */
public class SnapshotChangedException extends BusinessException {

    public SnapshotChangedException(PortfolioSpecId id) {
        super("The exchange state changed since specification [" + id.getId()
                + "] was built; it was recomputed and needs reviewing again");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_SNAPSHOT_CHANGED;
    }
}
