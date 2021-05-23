package com.multi.vidulum.portfolio.infrastructure.portfolio;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class DomainPortfolioRepositoryImpl implements DomainPortfolioRepository {

    private final PortfolioMongoRepository portfolioMongoRepository;

    @Override
    public Optional<Portfolio> findById(PortfolioId portfolioId) {
        return portfolioMongoRepository.findById(portfolioId.getId())
                .map(PortfolioEntity::toSnapshot)
                .map(Portfolio::from);
    }

    @Override
    public Portfolio save(Portfolio aggregate) {
        return Portfolio.from(
                portfolioMongoRepository.save(PortfolioEntity.fromSnapshot(aggregate.getSnapshot()))
                        .toSnapshot());
    }

    @Override
    public List<Portfolio> findByUserId(UserId userId) {
        return portfolioMongoRepository.findByUserId(userId.getId()).stream()
                .map(PortfolioEntity::toSnapshot)
                .map(Portfolio::from)
                .collect(Collectors.toList());
    }
}
