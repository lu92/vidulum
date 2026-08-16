package com.multi.vidulum.common;

import lombok.Value;

@Value
public class Range<T> {
    T start;
    T end;

    public static <T> Range<T> of(T start, T end) {
        return new Range<>(start, end);
    }
}
