package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.Status;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrderMongoRepository extends MongoRepository<OrderEntity, String> {
    List<OrderEntity> findByPortfolioIdAndStatus(String portfolioId, Status status);
}
