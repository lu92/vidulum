package com.multi.vidulum.cashflow.domain;

class CashChangeIsNotOpenedException extends RuntimeException {

    private CashChangeId id;
    private Type type;
    public CashChangeIsNotOpenedException(Type type, CashChangeId id) {
        super(String.format("Cash change [%s] [%s] is not opened", type, id));
        this.id = id;
        this.type = type;
    }
}
