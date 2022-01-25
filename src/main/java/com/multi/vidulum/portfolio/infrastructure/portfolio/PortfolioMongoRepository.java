package com.multi.vidulum.portfolio.infrastructure.portfolio;

import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PortfolioMongoRepository extends MongoRepository<PortfolioEntity, String> {

    Optional<PortfolioEntity> findByPortfolioId(String userId);

    List<PortfolioEntity> findByUserId(String userId);
}
