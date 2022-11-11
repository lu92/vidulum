package com.multi.vidulum.task.app.queries;

import com.multi.vidulum.shared.cqrs.queries.PageableQuery;
import com.multi.vidulum.task.domain.TaskStatus;
import lombok.Getter;
import org.springframework.data.domain.Pageable;

@Getter
public class GetTaskByStatusQuery extends PageableQuery {
    private final TaskStatus status;

    public GetTaskByStatusQuery(Pageable pageable, TaskStatus status) {
        super(pageable);
        this.status = status;
    }
}
