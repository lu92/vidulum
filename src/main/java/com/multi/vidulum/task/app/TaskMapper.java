package com.multi.vidulum.task.app;

import com.multi.vidulum.task.TaskDto;
import com.multi.vidulum.task.domain.Task;
import org.springframework.stereotype.Component;

@Component
public class TaskMapper {

    public TaskDto.TaskSummaryJson toJson(Task task) {
        return TaskDto.TaskSummaryJson.builder()
                .taskId(task.getTaskId().getId())
                .userId(task.getUserId().getId())
                .name(task.getName())
                .description(task.getDescription())
                .status(task.getStatus())
                .created(task.getCreated())
                .dueDate(task.getDueDate())
                .build();
    }
}
