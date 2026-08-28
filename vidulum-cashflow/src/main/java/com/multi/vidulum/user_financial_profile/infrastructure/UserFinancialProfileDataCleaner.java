package com.multi.vidulum.user_financial_profile.infrastructure;

import com.multi.vidulum.shared.DataCleaner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserFinancialProfileDataCleaner implements DataCleaner {

    @Override
    public void clean(MongoTemplate mongoTemplate) {
        mongoTemplate.dropCollection(UserFinancialProfileEntity.class);
    }
}
