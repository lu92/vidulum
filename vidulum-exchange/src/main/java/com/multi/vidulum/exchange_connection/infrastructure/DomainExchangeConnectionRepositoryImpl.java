package com.multi.vidulum.exchange_connection.infrastructure;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@AllArgsConstructor
public class DomainExchangeConnectionRepositoryImpl implements DomainExchangeConnectionRepository {

    private final ExchangeConnectionMongoRepository mongoRepository;

    @Override
    public ExchangeConnection save(ExchangeConnection connection) {
        return mongoRepository.save(ExchangeConnectionEntity.from(connection)).toDomain();
    }

    @Override
    public Optional<ExchangeConnection> findById(ExchangeConnectionId id) {
        return mongoRepository.findById(id.getId()).map(ExchangeConnectionEntity::toDomain);
    }

    @Override
    public Optional<ExchangeConnection> findByAccount(
            UserId userId, Broker broker, ExchangeEnvironment environment, String accountUid) {
        return mongoRepository
                .findByUserIdAndBrokerAndEnvironmentAndAccountUid(
                        userId.getId(), broker.getId(), environment.name(), accountUid)
                .map(ExchangeConnectionEntity::toDomain);
    }

    @Override
    public List<ExchangeConnection> findByUserId(UserId userId) {
        return mongoRepository.findByUserId(userId.getId()).stream()
                .map(ExchangeConnectionEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<ExchangeConnection> findByPortfolioId(PortfolioId portfolioId) {
        return mongoRepository.findByPortfolioId(portfolioId.getId())
                .map(ExchangeConnectionEntity::toDomain);
    }
}
