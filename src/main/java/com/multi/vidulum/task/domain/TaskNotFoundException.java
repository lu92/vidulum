package com.multi.vidulum.task.domain;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(TaskId taskId) {
        super(String.format("Task [%s] not found", taskId.getId()));
    }
}
