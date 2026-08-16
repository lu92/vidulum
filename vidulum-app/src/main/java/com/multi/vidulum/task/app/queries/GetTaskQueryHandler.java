package com.multi.vidulum.task.app.queries;

import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.task.domain.DomainTaskRepository;
import com.multi.vidulum.task.domain.Task;
import com.multi.vidulum.task.domain.TaskNotFoundException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetTaskQueryHandler implements QueryHandler<GetTaskQuery, Task> {

    private final DomainTaskRepository repository;
    @Override
    public Task query(GetTaskQuery query) {
        return repository.findById(query.getTaskId())
                .orElseThrow(() -> new TaskNotFoundException(query.getTaskId()));
    }
}
