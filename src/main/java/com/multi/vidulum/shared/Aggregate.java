package com.multi.vidulum.shared;

public interface Aggregate<ID, T extends EntitySnapshot<ID>> extends DomainEntity<ID, T> {
}
