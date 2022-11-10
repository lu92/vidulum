package com.multi.vidulum.task.infrastructure;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.task.domain.TaskId;
import com.multi.vidulum.task.domain.TaskSnapshot;
import com.multi.vidulum.task.domain.TaskStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

@Builder
@Getter
@ToString
@Document("task")
public class TaskEntity {
    @Id
    private String id;
    private String taskId;
    private String userId;
    private String name;
    private String description;
    private Date created;
    private Date dueDate;
    private TaskStatus status;

    public static TaskEntity fromSnapshot(TaskSnapshot snapshot) {
        Date dueDate = snapshot.getDueDate() != null ? Date.from(snapshot.getDueDate().toInstant()) : null;
        return TaskEntity.builder()
                .taskId(snapshot.getTaskId().getId())
                .userId(snapshot.getUserId().getId())
                .name(snapshot.getName())
                .description(snapshot.getDescription())
                .created(Date.from(snapshot.getCreated().toInstant()))
                .dueDate(dueDate)
                .status(snapshot.getStatus())
                .build();
    }

    public TaskSnapshot toSnapshot() {
        ZonedDateTime zonedDueDate = dueDate != null ? ZonedDateTime.ofInstant(dueDate.toInstant(), ZoneOffset.UTC) : null;
        return new TaskSnapshot(
                TaskId.of(taskId),
                UserId.of(userId),
                name,
                description,
                ZonedDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC),
                zonedDueDate,
                status
        );
    }
}
