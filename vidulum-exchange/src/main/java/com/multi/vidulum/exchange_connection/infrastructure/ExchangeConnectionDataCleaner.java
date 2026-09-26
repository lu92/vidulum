package com.multi.vidulum.exchange_connection.infrastructure;

import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
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
        mongoTemplate.remove(new Query(), ExchangeConnectionEntity.class);
    }
}
