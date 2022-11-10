package com.multi.vidulum;

import com.multi.vidulum.task.TaskDto;
import com.multi.vidulum.task.domain.TaskStatus;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(taskSummaryJson).isEqualToIgnoringGivenFields(
                TaskDto.TaskSummaryJson.builder()
                        .userId("lu92")
                        .name("task-1")
                        .description("task-1 description")
                        .status(TaskStatus.OPEN)
                        .build(),
                "taskId", "created"
        );
    }
}
