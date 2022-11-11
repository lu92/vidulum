package com.multi.vidulum.task.app.commands.close;

import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.task.domain.DomainTaskRepository;
import com.multi.vidulum.task.domain.Task;
import com.multi.vidulum.task.domain.TaskNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class CloseTaskCommandHandler implements CommandHandler<CloseTaskCommand, Task> {

    private final DomainTaskRepository repository;

    @Override
    public Task handle(CloseTaskCommand command) {
        Task task = repository.findById(command.getTaskId())
                .orElseThrow(() -> new TaskNotFoundException(command.getTaskId()));
        task.close(command.getComment());
        return repository.save(task);
    }
}
