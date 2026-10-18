package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Clears the recurring rules.
 *
 * <p>It used to drop the collection, which was the last place in the application still doing so —
 * see {@link DataCleaner} for why that silently removed the indexes created moments earlier at
 * startup.
 */
@Component
public class RecurringRuleDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), RecurringRuleEntity.class);
    }
}
