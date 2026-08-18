package com.multi.vidulum.user_financial_profile.infrastructure;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.user_financial_profile.domain.DomainUserFinancialProfileRepository;
import com.multi.vidulum.user_financial_profile.domain.UserFinancialProfile;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@AllArgsConstructor
public class DomainUserFinancialProfileRepositoryImpl implements DomainUserFinancialProfileRepository {

    private final UserFinancialProfileMongoRepository mongoRepository;

    @Override
    public UserFinancialProfile save(UserFinancialProfile profile) {
        UserFinancialProfileEntity saved = mongoRepository.save(UserFinancialProfileEntity.from(profile));
        return saved.toDomain();
    }

    @Override
    public Optional<UserFinancialProfile> findByUserId(UserId userId) {
        return mongoRepository.findById(userId.getId())
                .map(UserFinancialProfileEntity::toDomain);
    }

    @Override
    public boolean existsByUserId(UserId userId) {
        return mongoRepository.existsById(userId.getId());
    }
}
