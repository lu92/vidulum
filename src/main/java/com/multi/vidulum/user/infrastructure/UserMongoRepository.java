package com.multi.vidulum.user.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserMongoRepository extends MongoRepository<UserEntity, String> {
}
