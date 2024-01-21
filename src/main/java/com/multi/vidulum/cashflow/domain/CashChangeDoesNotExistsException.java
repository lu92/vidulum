package com.multi.vidulum.cashflow.domain;

public class CashChangeDoesNotExistsException extends RuntimeException {

    private CashChangeId id;

    public CashChangeDoesNotExistsException(CashChangeId id) {
        super(String.format("Cash change [%s] does not exists", id.id()));
        this.id = id;
    }
}
