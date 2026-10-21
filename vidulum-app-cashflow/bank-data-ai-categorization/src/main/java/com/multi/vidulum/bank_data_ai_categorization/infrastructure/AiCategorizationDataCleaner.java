package com.multi.vidulum.bank_data_ai_categorization.infrastructure;

import com.multi.vidulum.bank_data_ai_categorization.infrastructure.entity.PatternMappingEntity;
import com.multi.vidulum.shared.DataCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Empties this module's memory of patterns.
 *
 * <p>Its own cleaner rather than a line in ingestion's, because the subdomain that owns an entity is
 * the one that notices a new one - and this collection left {@code BankDataIngestionDataCleaner}
 * together with the entity.
 *
 * <p><b>Empties, never drops.</b> {@code dropCollection} would take the indexes with it, and the
 * cleaners run moments after startup created them from the annotations.
 */
@Slf4j
@Component
public class AiCategorizationDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), PatternMappingEntity.class);
        log.info("AI categorization patterns cleaned");
    }
}
