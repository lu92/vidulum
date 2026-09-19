package com.multi.vidulum.okx.exchange_connection.infrastructure;

import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops the OKX module's collections at startup, as required by {@code CLAUDE.md} for every new
 * {@code @Document}. Runs while the context is being built, before
 * {@link ExchangeConnectionIndexInitializer} recreates the index.
 */
@Component
public class OkxDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.dropCollection(ExchangeConnectionEntity.class);
    }
}
