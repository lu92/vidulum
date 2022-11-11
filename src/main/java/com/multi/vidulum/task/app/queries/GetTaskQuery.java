package com.multi.vidulum.task.app.queries;

import com.multi.vidulum.shared.cqrs.queries.Query;
import com.multi.vidulum.task.domain.TaskId;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GetTaskQuery implements Query {
    TaskId taskId;
}
