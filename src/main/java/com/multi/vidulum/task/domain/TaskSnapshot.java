package com.multi.vidulum.task.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
public class TaskSnapshot implements EntitySnapshot<TaskId> {
    TaskId taskId;
    UserId userId;
    String name;
    String description;
    ZonedDateTime created;
    ZonedDateTime dueDate;
    TaskStatus status;

    @Override
    public TaskId id() {
        return taskId;
    }
}
