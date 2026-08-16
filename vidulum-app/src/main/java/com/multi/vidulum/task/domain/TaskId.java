package com.multi.vidulum.task.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskId {
    String id;

    public static TaskId of(String id) {
        return new TaskId(id);
    }

    public static TaskId generate() {
        return TaskId.of(UUID.randomUUID().toString());
    }
}
