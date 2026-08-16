package com.multi.vidulum.recurring_rules.domain.exceptions;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Thrown when communication with CashFlow service fails.
 */
public class CashFlowCommunicationException extends RecurringRuleException {

    private final CashFlowId cashFlowId;
    private final String operation;

    public CashFlowCommunicationException(CashFlowId cashFlowId, String operation, Throwable cause) {
        super(String.format("Failed to %s for CashFlow [%s]", operation, cashFlowId.id()), cause);
        this.cashFlowId = cashFlowId;
        this.operation = operation;
    }

    public CashFlowCommunicationException(CashFlowId cashFlowId, String operation, String message) {
        super(String.format("Failed to %s for CashFlow [%s]: %s", operation, cashFlowId.id(), message));
        this.cashFlowId = cashFlowId;
        this.operation = operation;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.RECURRING_RULE_CASHFLOW_COMMUNICATION_ERROR;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }

    public String getOperation() {
        return operation;
    }
}
