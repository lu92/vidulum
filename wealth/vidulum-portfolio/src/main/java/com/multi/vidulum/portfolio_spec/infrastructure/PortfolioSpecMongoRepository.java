package com.multi.vidulum.portfolio_spec.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PortfolioSpecMongoRepository extends MongoRepository<PortfolioSpecEntity, String> {

    List<PortfolioSpecEntity> findByUserId(String userId);
}
