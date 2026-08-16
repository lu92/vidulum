package com.multi.vidulum.task.app;

import com.multi.vidulum.task.TaskDto;
import com.multi.vidulum.task.domain.Task;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskMapper {

    public TaskDto.TaskSummaryJson toJson(Task task) {
        List<TaskDto.CommentJson> comments = task.getComments().stream()
                .map(comment -> TaskDto.CommentJson.builder()
                        .message(comment.message())
                        .created(comment.created())
                        .build())
                .toList();
        return TaskDto.TaskSummaryJson.builder()
                .taskId(task.getTaskId().getId())
                .userId(task.getUserId().getId())
                .name(task.getName())
                .description(task.getDescription())
                .comments(comments)
                .status(task.getStatus())
                .created(task.getCreated())
                .dueDate(task.getDueDate())
                .build();
    }
}
