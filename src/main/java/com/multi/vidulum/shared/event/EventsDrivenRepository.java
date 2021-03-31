package com.multi.vidulum.shared.event;

import com.multi.vidulum.shared.Aggregate;
import com.multi.vidulum.shared.DomainRepository;

import java.util.List;

public interface EventsDrivenRepository<ID, T extends Aggregate<ID, ? extends SnapshotWithEvents<ID>>> extends DomainRepository<ID, T> {
    @Override
    default T save(T aggregate) {
        return append(aggregate.getSnapshot().events());
    }

    T append(List<? extends DomainEvent> events);
}
