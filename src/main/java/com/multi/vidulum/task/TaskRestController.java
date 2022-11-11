package com.multi.vidulum.task;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.task.app.TaskMapper;
import com.multi.vidulum.task.app.commands.close.CloseTaskCommand;
import com.multi.vidulum.task.app.commands.create.CreateTaskCommand;
import com.multi.vidulum.task.domain.Comment;
import com.multi.vidulum.task.domain.Task;
import com.multi.vidulum.task.domain.TaskId;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZonedDateTime;

@RestController
@AllArgsConstructor
public class TaskRestController {

    private final CommandGateway commandGateway;
    private final TaskMapper taskMapper;
    private final Clock clock;

    @PostMapping("/task")
    public TaskDto.TaskSummaryJson create(@RequestBody TaskDto.CreateTaskJson request) {
        CreateTaskCommand command = CreateTaskCommand.builder()
                .taskId(TaskId.generate())
                .userId(UserId.of(request.getUserId()))
                .name(request.getName())
                .description(request.getDescription())
                .created(ZonedDateTime.now(clock))
                .dueDate(request.getDueDate())
                .build();
        Task task = commandGateway.send(command);
        return taskMapper.toJson(task);
    }

    @PutMapping("/task/close")
    public TaskDto.TaskSummaryJson close(@RequestBody TaskDto.CloseTaskJson request) {
        CloseTaskCommand command = CloseTaskCommand.builder()
                .taskId(TaskId.of(request.getTaskId()))
                .comment(new Comment(request.getComment(), ZonedDateTime.now(clock)))
                .build();

        Task task = commandGateway.send(command);
        return taskMapper.toJson(task);
    }
}
