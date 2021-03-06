package com.multi.vidulum.shared.ddd;

public interface Aggregate<ID, T extends EntitySnapshot<ID>> extends DomainEntity<ID, T> {
}
