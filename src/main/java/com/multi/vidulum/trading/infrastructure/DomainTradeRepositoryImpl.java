package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.domain.DomainTradeRepository;
import com.multi.vidulum.trading.domain.Trade;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

@Component
@AllArgsConstructor
public class DomainTradeRepositoryImpl implements DomainTradeRepository {

    TradeMongoRepository mongoRepository;

    @Override
    public Optional<Trade> findById(TradeId tradeId) {
        return mongoRepository.findById(tradeId.getId())
                .map(TradeEntity::toSnapshot)
                .map(Trade::from);
    }

    @Override
    public Trade save(Trade aggregate) {
        return Trade.from(
                mongoRepository.save(TradeEntity.fromSnapshot(aggregate.getSnapshot()))
                        .toSnapshot());
    }

    @Override
    public List<Trade> findByUserIdAndPortfolioId(UserId userId, PortfolioId portfolioId) {
        return mongoRepository.findByUserIdAndPortfolioId(userId.getId(), portfolioId.getId())
                .stream()
                .map(TradeEntity::toSnapshot)
                .map(Trade::from)
                .collect(toList());
    }

    @Override
    public List<Trade> findByUserIdAndPortfolioIdInDateRange(UserId userId, ZonedDateTime from, ZonedDateTime to) {
        return mongoRepository.findByUserIdAndOriginDateTimeBetween(
                userId.getId(),
//                portfolioId.getId(),
                Date.from(from.toInstant()),
                Date.from(to.toInstant()))
                .stream()
                .map(TradeEntity::toSnapshot)
                .map(Trade::from)
                .collect(toList());
    }
}
