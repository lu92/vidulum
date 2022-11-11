package com.multi.vidulum.task.app.commands.close;

import com.multi.vidulum.shared.cqrs.commands.Command;
import com.multi.vidulum.task.domain.Comment;
import com.multi.vidulum.task.domain.TaskId;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CloseTaskCommand implements Command {
    TaskId taskId;
    Comment comment;
}
