package com.multi.vidulum.bank_data_ingestion.mappings.infrastructure;

import com.multi.vidulum.bank_data_ingestion.mappings.infrastructure.entity.CategoryMappingEntity;
import com.multi.vidulum.shared.DataCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Empties the category mappings.
 *
 * <p>Its own cleaner because this section owns the collection - the same reasoning that gave the AI
 * categorization module one in F66. It also removes a cycle: while the module-wide cleaner named
 * this entity, {@code infrastructure} depended on {@code mappings} while staging's handlers depended
 * on {@code infrastructure}, and {@code CanonicalRowIsSelfContainedTest} reported that.
 *
 * <p><b>Empties, never drops.</b> {@code dropCollection} would take the indexes with it, and the
 * cleaners run moments after startup created them from the annotations.
 */
@Slf4j
@Component
public class CategoryMappingDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), CategoryMappingEntity.class);
        log.info("Category mappings cleaned");
    }
}
