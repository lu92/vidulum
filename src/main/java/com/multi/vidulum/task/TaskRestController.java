package com.multi.vidulum.task;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.task.app.TaskMapper;
import com.multi.vidulum.task.app.commands.close.CloseTaskCommand;
import com.multi.vidulum.task.app.commands.create.CreateTaskCommand;
import com.multi.vidulum.task.app.queries.GetTaskByStatusQuery;
import com.multi.vidulum.task.app.queries.GetTaskQuery;
import com.multi.vidulum.task.domain.Comment;
import com.multi.vidulum.task.domain.Task;
import com.multi.vidulum.task.domain.TaskId;
import com.multi.vidulum.task.domain.TaskStatus;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.ZonedDateTime;

@RestController
@AllArgsConstructor
public class TaskRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
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

    @GetMapping("/task/{taskId}")
    public TaskDto.TaskSummaryJson getTask(@PathVariable("taskId") String taskId) {
        Task task = queryGateway.send(
                GetTaskQuery.builder()
                        .taskId(TaskId.of(taskId))
                        .build()
        );

        return taskMapper.toJson(task);
    }

    @GetMapping("/task")
    public Page<TaskDto.TaskSummaryJson> findByStatus(
            @RequestParam("status") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Task> tasks = queryGateway.send(
                new GetTaskByStatusQuery(
                        PageRequest.of(page, size), TaskStatus.valueOf(status))
        );
        return tasks.map(taskMapper::toJson);
    }
}
