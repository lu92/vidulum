package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationDocument;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.CategoryMappingEntity;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.ImportJobEntity;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.PatternMappingEntity;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagedTransactionEntity;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagingSessionEntity;
import com.multi.vidulum.cashflow.infrastructure.entity.CashFlowEntity;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity.CashFlowForecastStatementEntity;
import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class CashFlowDataCleaner implements DataCleaner {

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
        // CashFlow
        mongoTemplate.remove(new Query(), CashFlowEntity.class);
        mongoTemplate.remove(new Query(), CashFlowForecastEntity.class);
        mongoTemplate.remove(new Query(), CashFlowForecastStatementEntity.class);

        // Bank Data Ingestion
        mongoTemplate.remove(new Query(), StagingSessionEntity.class);
        mongoTemplate.remove(new Query(), StagedTransactionEntity.class);
        mongoTemplate.remove(new Query(), CategoryMappingEntity.class);
        mongoTemplate.remove(new Query(), ImportJobEntity.class);
        mongoTemplate.remove(new Query(), PatternMappingEntity.class);

        // Bank Data Adapter (AI CSV Transformation)
        mongoTemplate.remove(new Query(), AiCsvTransformationDocument.class);
        mongoTemplate.remove(new Query(), MappingRules.class);

    }
}
