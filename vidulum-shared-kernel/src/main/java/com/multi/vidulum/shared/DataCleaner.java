package com.multi.vidulum.shared;

import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Interface for module-specific MongoDB data cleanup at application startup.
 * Each Maven module registers its own implementation to clear its collections.
 */
public interface DataCleaner {
    void clean(MongoTemplate mongoTemplate);
}
