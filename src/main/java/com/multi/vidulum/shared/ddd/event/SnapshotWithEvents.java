package com.multi.vidulum.shared.ddd.event;
import com.multi.vidulum.shared.ddd.EntitySnapshot;

import java.util.List;

public interface SnapshotWithEvents<ID> extends EntitySnapshot<ID> {
    List<? extends DomainEvent> events();
}
