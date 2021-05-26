package com.multi.vidulum.trading.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TradeMongoRepository extends MongoRepository<TradeEntity, String> {
    List<TradeEntity> findByUserIdAndPortfolioId(String userId, String portfolioId);
}
