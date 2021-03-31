package com.multi.vidulum.shared.event;
import com.multi.vidulum.shared.EntitySnapshot;

import java.util.List;

public interface SnapshotWithEvents<ID> extends EntitySnapshot<ID> {
    List<? extends DomainEvent> events();
}