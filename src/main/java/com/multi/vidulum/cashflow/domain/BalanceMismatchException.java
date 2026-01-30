package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Money;

/**
 * Exception thrown when the confirmed balance does not match the calculated balance during activation.
 * This indicates that either imports are incomplete or the confirmed balance is incorrect.
 */
public class BalanceMismatchException extends RuntimeException {

    private final CashFlowId cashFlowId;
    private final Name cashFlowName;
    private final Money confirmedBalance;
    private final Money calculatedBalance;
    private final Money difference;

    public BalanceMismatchException(CashFlowId cashFlowId, Name cashFlowName, Money confirmedBalance, Money calculatedBalance, Money difference) {
        super(String.format("Balance mismatch for CashFlow '%s' [%s]. Confirmed: [%s], Calculated: [%s], Difference: [%s]. " +
                        "Use forceAttestation=true or createAdjustment=true to proceed.",
                cashFlowName.name(), cashFlowId.id(), confirmedBalance, calculatedBalance, difference));
        this.cashFlowId = cashFlowId;
        this.cashFlowName = cashFlowName;
        this.confirmedBalance = confirmedBalance;
        this.calculatedBalance = calculatedBalance;
        this.difference = difference;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }

    public Name getCashFlowName() {
        return cashFlowName;
    }

    public Money getConfirmedBalance() {
        return confirmedBalance;
    }

    public Money getCalculatedBalance() {
        return calculatedBalance;
    }

    public Money getDifference() {
        return difference;
    }
}
