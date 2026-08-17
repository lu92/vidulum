package com.multi.vidulum;

import com.multi.vidulum.pnl.infrastructure.entities.PnlHistoryEntity;
import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import com.multi.vidulum.security.token.Token;
import com.multi.vidulum.shared.DataCleaner;
import com.multi.vidulum.task.infrastructure.TaskEntity;
import com.multi.vidulum.trading.infrastructure.OrderEntity;
import com.multi.vidulum.trading.infrastructure.TradeEntity;
import com.multi.vidulum.user.infrastructure.UserEntity;
import com.multi.vidulum.user_financial_profile.infrastructure.UserFinancialProfileEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class CoreDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        // Security & User
        mongoTemplate.dropCollection(Token.class);
        mongoTemplate.dropCollection(UserEntity.class);
        mongoTemplate.dropCollection(UserFinancialProfileEntity.class);

        // Portfolio & Trading
        mongoTemplate.dropCollection(PortfolioEntity.class);
        mongoTemplate.dropCollection(TradeEntity.class);
        mongoTemplate.dropCollection(OrderEntity.class);

        // Other
        mongoTemplate.dropCollection(TaskEntity.class);
        mongoTemplate.dropCollection(PnlHistoryEntity.class);
    }
}
