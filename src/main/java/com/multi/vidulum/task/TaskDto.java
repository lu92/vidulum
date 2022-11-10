package com.multi.vidulum.task;

import com.multi.vidulum.task.domain.TaskStatus;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

public final class TaskDto {

    @Data
    @Builder
    public static class CreateTaskJson {
        private String name;
        private String userId;
        private String description;
        private ZonedDateTime dueDate; // optional
    }

    @Data
    @Builder
    public static class TaskSummaryJson {
        private String taskId;
        private String userId;
        private String name;
        private String description;
        private TaskStatus status;
        private ZonedDateTime created;
        private ZonedDateTime dueDate; // optional
    }
}
