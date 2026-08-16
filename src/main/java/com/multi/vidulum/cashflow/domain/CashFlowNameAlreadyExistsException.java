package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Exception thrown when attempting to create a CashFlow with a name that already exists
 * for the given user.
 */
public class CashFlowNameAlreadyExistsException extends BusinessException {

    private final String cashFlowName;
    private final UserId userId;

    public CashFlowNameAlreadyExistsException(String cashFlowName, UserId userId) {
        super(String.format(
                "CashFlow with name '%s' already exists for user '%s'",
                cashFlowName, userId.getId()));
        this.cashFlowName = cashFlowName;
        this.userId = userId;
    }

    public String getCashFlowName() {
        return cashFlowName;
    }

    public UserId getUserId() {
        return userId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CASHFLOW_NAME_ALREADY_EXISTS;
    }
}
