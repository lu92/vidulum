package com.multi.vidulum.shared.cqrs.queries;

import lombok.AllArgsConstructor;
import org.springframework.data.domain.Pageable;

@AllArgsConstructor
public abstract class PageableQuery implements Query {
    protected Pageable pageable;

    public Pageable getPageable() {
        return pageable;
    }
}
