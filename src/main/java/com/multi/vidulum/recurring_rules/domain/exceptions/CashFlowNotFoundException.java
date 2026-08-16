package com.multi.vidulum.recurring_rules.domain.exceptions;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Thrown when a cash flow is not found when creating or updating a rule.
 */
public class CashFlowNotFoundException extends RecurringRuleException {

    private final CashFlowId cashFlowId;

    public CashFlowNotFoundException(CashFlowId cashFlowId) {
        super("CashFlow not found: " + cashFlowId.id());
        this.cashFlowId = cashFlowId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CASHFLOW_NOT_FOUND;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }
}
