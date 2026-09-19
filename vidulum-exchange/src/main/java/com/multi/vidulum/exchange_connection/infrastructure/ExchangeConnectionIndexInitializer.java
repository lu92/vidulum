package com.multi.vidulum.exchange_connection.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Creates the unique index over the connection's natural key.
 *
 * <p>Two reasons this is a runner rather than a {@code @CompoundIndex} annotation:
 *
 * <ul>
 *   <li>Spring Data's automatic index creation defaults to off and this project never enables it,
 *       so annotated indexes in this codebase never reach MongoDB. Declaring the key without
 *       creating it would be worse than not declaring it — the invariant would look enforced.
 *   <li>{@code DataCleaner} beans drop their collections while the context is still being built.
 *       An {@link ApplicationRunner} runs after the context is ready, so the index is created
 *       after the drop rather than being wiped by it.
 * </ul>
 *
 * <p>The key is {@code (userId, broker, environment, accountUid)} — scoped to the user, so one
 * user connecting an account cannot lock another user out of connecting the same one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeConnectionIndexInitializer implements ApplicationRunner {

    static final String NATURAL_KEY_INDEX = "exchange_connection_natural_key";

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Index naturalKey = new Index()
                .on("userId", Sort.Direction.ASC)
                .on("broker", Sort.Direction.ASC)
                .on("environment", Sort.Direction.ASC)
                .on("accountUid", Sort.Direction.ASC)
                .named(NATURAL_KEY_INDEX)
                .unique();

        mongoTemplate.indexOps(ExchangeConnectionEntity.class).createIndex(naturalKey);
        log.info("Ensured unique index [{}] on exchange_connections", NATURAL_KEY_INDEX);
    }
}
