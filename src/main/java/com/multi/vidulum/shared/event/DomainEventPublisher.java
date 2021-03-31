package com.multi.vidulum.shared.event;

public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
