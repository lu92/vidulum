package com.multi.vidulum.cashflow.domain;

public class CashFlowDoesNotExistsException extends RuntimeException {

    private CashFlowId id;

    public CashFlowDoesNotExistsException(CashFlowId id) {
        super(String.format("Cash flow [%s] does not exists", id));
        this.id = id;
    }
}
