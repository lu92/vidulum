package com.multi.vidulum.task.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
@Data
@Builder
public class Task implements Aggregate<TaskId, TaskSnapshot> {
    private TaskId taskId;
    private UserId userId;
    private String name;
    private String description;
    private ZonedDateTime created;
    private ZonedDateTime dueDate;
    private TaskStatus status;

    @Override
    public TaskSnapshot getSnapshot() {
        return new TaskSnapshot(
                taskId,
                userId,
                name,
                description,
                created,
                dueDate,
                status
        );
    }

    public static Task from(TaskSnapshot snapshot) {
        return Task.builder()
                .taskId(snapshot.getTaskId())
                .userId(snapshot.getUserId())
                .name(snapshot.getName())
                .description(snapshot.getDescription())
                .created(snapshot.getCreated())
                .dueDate(snapshot.getDueDate())
                .status(snapshot.getStatus())
                .build();
    }

}
