package com.multi.vidulum.task.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface TaskMongoRepository extends MongoRepository<TaskEntity, String> {

    Optional<TaskEntity> findByTaskId(String taskId);
}
