package com.multi.vidulum.okx.exchange_connection.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ExchangeConnectionMongoRepository
        extends MongoRepository<ExchangeConnectionEntity, String> {

    Optional<ExchangeConnectionEntity> findByUserIdAndExchangeAndEnvironmentAndAccountUid(
            String userId, String exchange, String environment, String accountUid);

    List<ExchangeConnectionEntity> findByUserId(String userId);

    Optional<ExchangeConnectionEntity> findByPortfolioId(String portfolioId);
}
