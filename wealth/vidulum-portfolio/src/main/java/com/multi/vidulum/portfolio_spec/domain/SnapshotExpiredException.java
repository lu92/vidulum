package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * The reading this specification is anchored to has aged out (task D10).
 *
 * <p>Raised when an answer arrives against an expired anchor. Refusing here rather than at
 * confirmation is the whole point: {@code confirm} would refuse anyway once it compared with a
 * fresh snapshot (D11), but by then the owner has answered everything. Losing the work at the
 * start is recoverable; losing it at the end is how people abandon onboarding.
 */
public class SnapshotExpiredException extends BusinessException {

    public SnapshotExpiredException(PortfolioSpecId id, ZonedDateTime takenAt, Duration ttl) {
        super(String.format(
                "Specification [%s] is anchored to a reading from [%s], older than [%s]; "
                        + "read the account again before answering",
                id.getId(), takenAt, ttl));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_SNAPSHOT_EXPIRED;
    }
}
