package com.multi.vidulum.task.infrastructure;

import com.multi.vidulum.task.domain.DomainTaskRepository;
import com.multi.vidulum.task.domain.Task;
import com.multi.vidulum.task.domain.TaskId;
import com.multi.vidulum.task.domain.TaskStatus;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@AllArgsConstructor
public class DomainTaskImplRepository implements DomainTaskRepository {

    private final TaskMongoRepository repository;

    @Override
    public Optional<Task> findById(TaskId taskId) {
        return repository.findByTaskId(taskId.getId())
                .map(TaskEntity::toSnapshot)
                .map(Task::from);
    }

    @Override
    public Task save(Task aggregate) {
        TaskEntity taskEntity = TaskEntity.fromSnapshot(aggregate.getSnapshot());
        TaskEntity savedTradeEntity = repository.save(taskEntity);
        return Task.from(
                savedTradeEntity
                        .toSnapshot());
    }

    @Override
    public Page<Task> findByStatus(TaskStatus status, Pageable pageable) {
        return repository.findByStatus(status, pageable)
                .map(TaskEntity::toSnapshot)
                .map(Task::from);
    }
}
