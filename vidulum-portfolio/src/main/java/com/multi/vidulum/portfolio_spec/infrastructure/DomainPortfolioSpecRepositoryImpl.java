package com.multi.vidulum.portfolio_spec.infrastructure;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.DomainPortfolioSpecRepository;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@AllArgsConstructor
public class DomainPortfolioSpecRepositoryImpl implements DomainPortfolioSpecRepository {

    private final PortfolioSpecMongoRepository mongoRepository;

    @Override
    public PortfolioSpec save(PortfolioSpec spec) {
        return mongoRepository.save(PortfolioSpecEntity.from(spec)).toDomain();
    }

    @Override
    public Optional<PortfolioSpec> findById(PortfolioSpecId id) {
        return mongoRepository.findById(id.getId()).map(PortfolioSpecEntity::toDomain);
    }

    @Override
    public List<PortfolioSpec> findByUserId(UserId userId) {
        return mongoRepository.findByUserId(userId.getId()).stream()
                .map(PortfolioSpecEntity::toDomain)
                .toList();
    }
}
