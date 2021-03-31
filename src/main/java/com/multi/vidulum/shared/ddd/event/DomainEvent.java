package com.multi.vidulum.shared.ddd.event;

import java.time.Instant;

public interface DomainEvent {
    Instant occurredOn();
}
