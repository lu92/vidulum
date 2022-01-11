package com.multi.vidulum.common;

import lombok.Value;

@Value(staticConstructor = "of")
public class OcoGroup {
    String id;

    private static final String NOT_DEFINED = "NOT-DEFINED";

    public static OcoGroup notDefined() {
        return OcoGroup.of(NOT_DEFINED);
    }

    public boolean isNotDefined() {
        return id.equals(NOT_DEFINED);
    }
}
