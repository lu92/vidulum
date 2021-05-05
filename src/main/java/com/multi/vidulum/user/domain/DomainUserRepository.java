package com.multi.vidulum.user.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.DomainRepository;

public interface DomainUserRepository extends DomainRepository<UserId, User> {
}
