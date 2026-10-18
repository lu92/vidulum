package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.infrastructure.entity.CashFlowEntity;
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
     *
     * <p>One collection, because that is all this subdomain owns. It used to empty ten, across
     * four subdomains: the forecast projection, bank-data-ingestion's five staging collections and
     * bank-data-adapter's two AI caches. Each of those has its own cleaner now, next to the
     * entities it empties - the arrangement step 03 established everywhere except here.
     */
    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), CashFlowEntity.class);
    }
}
