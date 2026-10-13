package com.multi.vidulum.pnl.infrastructure;

import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import com.multi.vidulum.pnl.infrastructure.entities.PnlHistoryEntity;
/**
 * Recorded profit and loss history.
 *
 * <p>Split out of {@code WealthDataCleaner}, which sat in {@code trading.infrastructure} and
 * reached for the persistence classes of three other subdomains. Clearing a module's own
 * collections is the module's business, and the one that owns the entity is the one that knows when
 * a new one appears.
 */
@Component
public class PnlDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), PnlHistoryEntity.class);
    }
}
