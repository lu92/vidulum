package com.multi.vidulum.task.app.commands.create;

import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.task.domain.DomainTaskRepository;
import com.multi.vidulum.task.domain.Task;
import com.multi.vidulum.task.domain.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class CreateTaskCommandHandler implements CommandHandler<CreateTaskCommand, Task> {
    private final DomainTaskRepository repository;

    @Override
    public Task handle(CreateTaskCommand command) {
        Task newTask = Task.builder()
                .taskId(command.getTaskId())
                .userId(command.getUserId())
                .name(command.getName())
                .description(command.getDescription())
                .created(command.getCreated())
                .dueDate(command.getDueDate())
                .status(TaskStatus.OPEN)
                .build();

        return repository.save(newTask);
    }
}
