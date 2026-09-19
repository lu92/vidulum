package com.multi.vidulum.exchange_connection.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ExchangeConnectionMongoRepository
        extends MongoRepository<ExchangeConnectionEntity, String> {

    Optional<ExchangeConnectionEntity> findByUserIdAndBrokerAndEnvironmentAndAccountUid(
            String userId, String broker, String environment, String accountUid);

    List<ExchangeConnectionEntity> findByUserId(String userId);

    Optional<ExchangeConnectionEntity> findByPortfolioId(String portfolioId);
}
