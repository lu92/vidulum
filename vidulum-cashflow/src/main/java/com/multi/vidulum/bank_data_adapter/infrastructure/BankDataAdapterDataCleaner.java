package com.multi.vidulum.bank_data_adapter.infrastructure;

import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationDocument;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules;
import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Empties what the AI CSV transformation keeps: the transformations themselves and the mapping
 * rules learned from them.
 *
 * <p>Moved out of {@code CashFlowDataCleaner} for the reason step 03 established - the subdomain
 * that owns an entity notices when a new one appears. It matters more here than elsewhere: a
 * transformation is a <b>cache of an AI answer</b>, so a collection left behind makes a test or a
 * manual run reuse a previous model's output and look deterministic when it is not. That is why
 * the manual testing guide insists on {@code down -v} between runs.
 *
 * <p>Empties, never drops - see {@link DataCleaner}.
 */
@Component
public class BankDataAdapterDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), AiCsvTransformationDocument.class);
        mongoTemplate.remove(new Query(), MappingRules.class);
    }
}
