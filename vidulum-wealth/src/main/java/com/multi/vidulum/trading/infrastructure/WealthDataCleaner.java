package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.pnl.infrastructure.entities.PnlHistoryEntity;
import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import com.multi.vidulum.portfolio_spec.infrastructure.PortfolioSpecEntity;
import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class WealthDataCleaner implements DataCleaner {

    /**
     * Empties the collections; it used to drop them.
     *
     * <p>Dropping takes the indexes with it. They are created at startup from the annotations, and
     * the cleaner runs just afterwards — so every index in this application lived for a fraction of
     * a second and then vanished, and the first write recreated the collection bare. The
     * uniqueness task F1 depends on survived the tests and did not survive the container.
     *
     * <p>Removing documents leaves the collection and its indexes in place, which is what "start
     * from clean data" was always supposed to mean.
     */
    @Override
    public void clean(MongoTemplate mongoTemplate) {
        // Portfolio
        mongoTemplate.remove(new Query(), PortfolioEntity.class);
        mongoTemplate.remove(new Query(), PortfolioSpecEntity.class);

        // Trading
        mongoTemplate.remove(new Query(), OrderEntity.class);
        mongoTemplate.remove(new Query(), TradeEntity.class);

        // PnL
        mongoTemplate.remove(new Query(), PnlHistoryEntity.class);
    }
}
