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
import java.util.List;

@Builder
@Getter
@ToString
@Document("task")
public class TaskEntity {
    @Id
    private String taskId;
    private String userId;
    private String name;
    private String description;
    private List<CommentEntity> comments;
    private Date created;
    private Date dueDate;
    private TaskStatus status;

    public static TaskEntity fromSnapshot(TaskSnapshot snapshot) {
        Date dueDate = snapshot.getDueDate() != null ? Date.from(snapshot.getDueDate().toInstant()) : null;
        List<CommentEntity> commentEntities = snapshot.getComments().stream()
                .map(commentSnapshot -> new CommentEntity(
                        commentSnapshot.message(),
                        Date.from(commentSnapshot.created().toInstant())))
                .toList();
        return TaskEntity.builder()
                .taskId(snapshot.getTaskId().getId())
                .userId(snapshot.getUserId().getId())
                .name(snapshot.getName())
                .description(snapshot.getDescription())
                .comments(commentEntities)
                .created(Date.from(snapshot.getCreated().toInstant()))
                .dueDate(dueDate)
                .status(snapshot.getStatus())
                .build();
    }

    public TaskSnapshot toSnapshot() {
        ZonedDateTime zonedDueDate = dueDate != null ? ZonedDateTime.ofInstant(dueDate.toInstant(), ZoneOffset.UTC) : null;
        List<TaskSnapshot.CommentSnapshot> commentSnapshots = comments.stream()
                .map(commentEntity -> new TaskSnapshot.CommentSnapshot(
                        commentEntity.message(),
                        ZonedDateTime.ofInstant(commentEntity.created().toInstant(), ZoneOffset.UTC)))
                .toList();
        return new TaskSnapshot(
                TaskId.of(taskId),
                UserId.of(userId),
                name,
                description,
                commentSnapshots,
                ZonedDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC),
                zonedDueDate,
                status
        );
    }

    public record CommentEntity(
            String message,
            Date created
    ) {
    }
}
