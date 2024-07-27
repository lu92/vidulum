package com.multi.vidulum.user.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.DomainRepository;

import java.util.Optional;

public interface DomainUserRepository extends DomainRepository<UserId, User> {
    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

}
