package com.multi.vidulum.task.app.queries;

import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.task.domain.DomainTaskRepository;
import com.multi.vidulum.task.domain.Task;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetTaskByStatusQueryHandler implements QueryHandler<GetTaskByStatusQuery, Page<Task>> {

    private final DomainTaskRepository repository;

    @Override
    public Page<Task> query(GetTaskByStatusQuery query) {
        return repository.findByStatus(query.getStatus(), query.getPageable());
    }
}
