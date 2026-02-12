package com.multi.vidulum.cashflow.domain;

/**
 * Exception thrown when a month rollover operation is not allowed.
 * <p>
 * This can happen when:
 * <ul>
 *   <li>CashFlow is not in OPEN status</li>
 *   <li>CashFlow is in SETUP mode (not yet activated)</li>
 *   <li>CashFlow is CLOSED</li>
 * </ul>
 */
public class RolloverNotAllowedException extends RuntimeException {

    private final CashFlowId cashFlowId;

    public RolloverNotAllowedException(CashFlowId cashFlowId, String message) {
        super(message);
        this.cashFlowId = cashFlowId;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }
}
