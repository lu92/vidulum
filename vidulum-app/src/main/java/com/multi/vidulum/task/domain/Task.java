package com.multi.vidulum.task.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
public class Task implements Aggregate<TaskId, TaskSnapshot> {
    private TaskId taskId;
    private UserId userId;
    private String name;
    private String description;
    private List<Comment> comments;
    private ZonedDateTime created;
    private ZonedDateTime dueDate;
    private TaskStatus status;

    @Override
    public TaskSnapshot getSnapshot() {
        List<TaskSnapshot.CommentSnapshot> commentSnapshots = comments.stream()
                .map(comment -> new TaskSnapshot.CommentSnapshot(comment.message(), comment.created()))
                .toList();
        return new TaskSnapshot(
                taskId,
                userId,
                name,
                description,
                commentSnapshots,
                created,
                dueDate,
                status
        );
    }

    public static Task from(TaskSnapshot snapshot) {
        List<Comment> mappedComments = snapshot.getComments().stream()
                .map(commentSnapshot -> new Comment(commentSnapshot.message(), commentSnapshot.created()))
                .collect(Collectors.toList());
        return Task.builder()
                .taskId(snapshot.getTaskId())
                .userId(snapshot.getUserId())
                .name(snapshot.getName())
                .description(snapshot.getDescription())
                .comments(mappedComments)
                .created(snapshot.getCreated())
                .dueDate(snapshot.getDueDate())
                .status(snapshot.getStatus())
                .build();
    }

    public void close(Comment comment) {
        comments.add(comment);
        status = TaskStatus.CLOSED;
    }
}
