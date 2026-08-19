package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.pnl.infrastructure.entities.PnlHistoryEntity;
import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class WealthDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        // Portfolio
        mongoTemplate.dropCollection(PortfolioEntity.class);

        // Trading
        mongoTemplate.dropCollection(OrderEntity.class);
        mongoTemplate.dropCollection(TradeEntity.class);

        // PnL
        mongoTemplate.dropCollection(PnlHistoryEntity.class);
    }
}
