package com.multi.vidulum.user_financial_profile.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserFinancialProfileMongoRepository extends MongoRepository<UserFinancialProfileEntity, String> {
}
