package com.multi.vidulum.shared;

import java.util.function.Predicate;

@FunctionalInterface
public interface Specification<T> extends Predicate<T> {
    default boolean isSatisfiedBy(T objectToTest) {
        return test(objectToTest);
    }
}