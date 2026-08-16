package com.multi.vidulum.task.infrastructure;

import com.multi.vidulum.task.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface TaskMongoRepository extends MongoRepository<TaskEntity, String> {

    Optional<TaskEntity> findByTaskId(String taskId);

    Page<TaskEntity> findByStatus(TaskStatus status, Pageable pageable);
}
