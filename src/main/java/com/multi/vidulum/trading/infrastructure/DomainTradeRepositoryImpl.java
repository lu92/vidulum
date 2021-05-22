package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.trading.domain.DomainTradeRepository;
import com.multi.vidulum.trading.domain.Trade;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
}
