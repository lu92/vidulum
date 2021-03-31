package com.multi.vidulum.shared.event;

import java.time.Instant;

public interface DomainEvent {
    Instant occurredOn();
}
