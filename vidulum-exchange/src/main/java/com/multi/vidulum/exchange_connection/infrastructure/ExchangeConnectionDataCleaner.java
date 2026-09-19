package com.multi.vidulum.exchange_connection.infrastructure;

import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops this module's collections at startup, as required by {@code CLAUDE.md} for every new
 * {@code @Document}. Runs while the context is being built, before
 * {@link ExchangeConnectionIndexInitializer} recreates the index.
 *
 * <p>It lives here rather than in an exchange's module because {@code exchange_connections} holds
 * every exchange's connections in one collection — two exchange modules each dropping it would
 * be harmless but wrong.
 */
@Component
public class ExchangeConnectionDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.dropCollection(ExchangeConnectionEntity.class);
    }
}
