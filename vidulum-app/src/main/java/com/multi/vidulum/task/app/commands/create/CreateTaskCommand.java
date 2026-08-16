package com.multi.vidulum.task.app.commands.create;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import com.multi.vidulum.task.domain.TaskId;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class CreateTaskCommand implements Command {
    TaskId taskId;
    UserId userId;
    String name;
    String description;
    ZonedDateTime created;
    ZonedDateTime dueDate; // optional
}
