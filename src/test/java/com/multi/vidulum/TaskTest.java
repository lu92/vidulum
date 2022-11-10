package com.multi.vidulum;

import com.multi.vidulum.task.TaskDto;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;

public class TaskTest extends IntegrationTest {

    @Test
    public void shouldCreateTask() {

        TaskDto.TaskSummaryJson taskSummaryJson = taskRestController.create(
                TaskDto.CreateTaskJson.builder()
                        .name("task-1")
                        .userId("lu92")
                        .description("task-1 description")
                        .build()
        );
        System.out.println(taskSummaryJson);
    }
}
