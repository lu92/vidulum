package com.multi.vidulum.task;

import com.multi.vidulum.task.domain.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

public final class TaskDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateTaskJson {
        private String name;
        private String userId;
        private String description;
        private ZonedDateTime dueDate; // optional
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskSummaryJson {
        private String taskId;
        private String userId;
        private String name;
        private String description;
        private List<CommentJson> comments;
        private TaskStatus status;
        private ZonedDateTime created;
        private ZonedDateTime dueDate; // optional
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommentJson {
        private String message;
        private ZonedDateTime created;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloseTaskJson {
        private String taskId;
        private String comment;
    }
}
