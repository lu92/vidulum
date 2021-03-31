package com.multi.vidulum.shared.ddd.event;

public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
