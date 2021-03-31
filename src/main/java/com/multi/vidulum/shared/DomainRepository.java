package com.multi.vidulum.shared;

import java.util.Optional;

public interface DomainRepository<ID, T extends Aggregate<ID, ? extends EntitySnapshot<ID>>> {
    Optional<T> findById(ID id);
    T save(T aggregate);
}
