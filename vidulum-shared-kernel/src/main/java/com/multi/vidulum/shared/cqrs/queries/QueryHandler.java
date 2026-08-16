package com.multi.vidulum.shared.cqrs.queries;

public interface QueryHandler<T extends Query, R> {
    R query(T query);
}
