package com.multi.vidulum.portfolio.infrastructure.portfolio;

import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PortfolioMongoRepository extends MongoRepository<PortfolioEntity, String> {

    List<PortfolioEntity> findByUserId(String userId);
}
