package com.multi.vidulum.portfolio.infrastructure.portfolio;

import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PortfolioMongoRepository extends MongoRepository<PortfolioEntity, String> {
}
