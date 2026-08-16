package com.multi.vidulum.shared.ddd;

public interface DomainEntity<ID, T extends EntitySnapshot<ID>> {
    T getSnapshot();
}
