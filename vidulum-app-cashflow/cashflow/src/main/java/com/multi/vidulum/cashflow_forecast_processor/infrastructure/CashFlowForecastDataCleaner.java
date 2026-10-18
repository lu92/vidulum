package com.multi.vidulum.cashflow_forecast_processor.infrastructure;

import com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity.CashFlowForecastStatementEntity;
import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Empties the forecast projection.
 *
 * <p>Both collections are <b>derived</b> - rebuilt from the {@code cash_flow} events this
 * subdomain consumes - which is the opposite of the cashflow aggregate next door, where the
 * documents are the source of truth. Mixing the two in one cleaner hid that difference; it also
 * meant the projection's collections were emptied by a class that knows nothing about how they are
 * rebuilt.
 *
 * <p>Empties, never drops - see {@link DataCleaner}.
 */
@Component
public class CashFlowForecastDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), CashFlowForecastEntity.class);
        mongoTemplate.remove(new Query(), CashFlowForecastStatementEntity.class);
    }
}
