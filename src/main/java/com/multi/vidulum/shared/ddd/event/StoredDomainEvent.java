package com.multi.vidulum.shared.ddd.event;

import java.time.Instant;

public interface StoredDomainEvent {
    String index();
    DomainEvent event();
    Instant occurredOn();
}
