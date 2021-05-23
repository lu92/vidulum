package com.multi.vidulum.trading.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface TradeMongoRepository extends MongoRepository<TradeEntity, String> {
}
