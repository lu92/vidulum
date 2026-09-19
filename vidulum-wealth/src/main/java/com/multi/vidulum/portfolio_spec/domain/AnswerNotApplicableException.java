package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * The answer does not match anything the specification is asking.
 *
 * <p>The specification is untrusted input and the snapshot is the anchor (§4.7): an answer that
 * names a position the snapshot does not report, or a batch of a different size, is rejected
 * rather than quietly applied to whatever looks closest.
 */
public class AnswerNotApplicableException extends BusinessException {

    public AnswerNotApplicableException(String message) {
        super(message);
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_ANSWER_NOT_APPLICABLE;
    }
}
