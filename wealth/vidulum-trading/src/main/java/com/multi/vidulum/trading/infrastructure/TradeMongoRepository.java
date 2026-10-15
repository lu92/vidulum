package com.multi.vidulum.trading.infrastructure;
import com.multi.vidulum.common.PortfolioId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface TradeMongoRepository extends MongoRepository<TradeEntity, String> {
    List<TradeEntity> findByUserIdAndPortfolioId(String userId, String portfolioId);

    Optional<TradeEntity> findByPortfolioIdAndOriginTradeId(String portfolioId, String originTradeId);

    List<TradeEntity> findByUserIdAndOriginDateTimeBetween(
            String userId,
            Date from,
            Date to);
}
