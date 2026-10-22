package com.multi.vidulum.bank_data_ingestion.staging.infrastructure;

import com.multi.vidulum.bank_data_ingestion.staging.infrastructure.entity.StagedTransactionEntity;
import com.multi.vidulum.bank_data_ingestion.staging.infrastructure.entity.StagingSessionEntity;
import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Empties this subdomain's five collections.
 *
 * <p>They used to be emptied by {@code CashFlowDataCleaner}, which is the arrangement step 03
 * replaced everywhere else: the subdomain that owns an entity is the one that notices when a new
 * one appears, and a cleaner living next to the entities survives extracting them into their own
 * artifact. Staging is where the ingestion flow keeps everything it has not committed yet, so a
 * forgotten collection here shows up as yesterday's half-finished import reappearing in a test.
 *
 * <p>Empties, never drops — dropping takes the indexes with it, and they are created from the
 * annotations moments before this runs. The rule is on {@link DataCleaner} itself.
 */
@Component
public class StagingDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), StagingSessionEntity.class);
        mongoTemplate.remove(new Query(), StagedTransactionEntity.class);
    }
}
