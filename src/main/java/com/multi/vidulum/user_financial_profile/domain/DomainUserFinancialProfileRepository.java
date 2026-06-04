package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.UserId;

import java.util.Optional;

public interface DomainUserFinancialProfileRepository {

    UserFinancialProfile save(UserFinancialProfile profile);

    Optional<UserFinancialProfile> findByUserId(UserId userId);

    boolean existsByUserId(UserId userId);
}
