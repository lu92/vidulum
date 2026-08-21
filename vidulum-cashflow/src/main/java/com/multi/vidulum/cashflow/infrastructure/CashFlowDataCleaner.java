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
import com.multi.vidulum.user_financial_profile.infrastructure.UserFinancialProfileEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class CashFlowDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        // CashFlow
        mongoTemplate.dropCollection(CashFlowEntity.class);
        mongoTemplate.dropCollection(CashFlowForecastEntity.class);
        mongoTemplate.dropCollection(CashFlowForecastStatementEntity.class);

        // Bank Data Ingestion
        mongoTemplate.dropCollection(StagingSessionEntity.class);
        mongoTemplate.dropCollection(StagedTransactionEntity.class);
        mongoTemplate.dropCollection(CategoryMappingEntity.class);
        mongoTemplate.dropCollection(ImportJobEntity.class);
        mongoTemplate.dropCollection(PatternMappingEntity.class);

        // Bank Data Adapter (AI CSV Transformation)
        mongoTemplate.dropCollection(AiCsvTransformationDocument.class);
        mongoTemplate.dropCollection(MappingRules.class);

        // User Financial Profile
        mongoTemplate.dropCollection(UserFinancialProfileEntity.class);
    }
}
