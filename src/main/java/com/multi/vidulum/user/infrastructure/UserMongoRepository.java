package com.multi.vidulum.user.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserMongoRepository extends MongoRepository<UserEntity, String> {
    Optional<UserEntity> findByUsername(String email);

    boolean existsByUsername(String email);
}
