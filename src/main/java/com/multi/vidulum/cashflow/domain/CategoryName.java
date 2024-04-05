package com.multi.vidulum.cashflow.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record CategoryName(String name) {

    private static final String NOT_DEFINED_VALUE = "Not defined";

    public static final CategoryName NOT_DEFINED = new CategoryName(NOT_DEFINED_VALUE);
    @JsonIgnore
    public boolean isDefined() {
        return !NOT_DEFINED_VALUE.equals(name);
    }
}
