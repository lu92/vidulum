package com.multi.vidulum.shared;

public interface DomainEntity<ID, T extends EntitySnapshot<ID>> {
    T getSnapshot();
}
