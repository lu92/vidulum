package com.multi.vidulum.bank_data_ingestion.importing.infrastructure;

import com.multi.vidulum.bank_data_ingestion.importing.infrastructure.entity.ImportJobEntity;
import com.multi.vidulum.shared.DataCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Empties the import jobs.
 *
 * <p>Its own cleaner for the same reason as the mappings one: the section that owns an entity is the
 * one that notices a new one, and the module-wide cleaner naming this entity was one leg of a cycle
 * between the sections.
 *
 * <p><b>Empties, never drops</b> - dropping takes the indexes, which are created at startup moments
 * before the cleaners run.
 */
@Slf4j
@Component
public class ImportJobDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), ImportJobEntity.class);
        log.info("Import jobs cleaned");
    }
}
