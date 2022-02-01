package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.OrderStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface OrderMongoRepository extends MongoRepository<OrderEntity, String> {
    List<OrderEntity> findByPortfolioIdAndStatus(String portfolioId, OrderStatus status);

    Optional<OrderEntity> findByOriginOrderId(String orderId);

    Optional<OrderEntity> findByOrderId(String orderId);
}
