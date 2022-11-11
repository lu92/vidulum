package com.multi.vidulum;

import com.multi.vidulum.task.TaskDto;
import com.multi.vidulum.task.domain.TaskStatus;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TaskTest extends IntegrationTest {

    private final ZonedDateTime _01_01_2022 = ZonedDateTime.parse("2022-01-01T00:00Z");
    @Test
    public void shouldCreateAndCloseTask() {

        TaskDto.TaskSummaryJson taskCreationSummary = taskRestController.create(
                TaskDto.CreateTaskJson.builder()
                        .name("task-1")
                        .userId("lu92")
                        .description("task-1 description")
                        .build()
        );
        assertThat(taskCreationSummary).isEqualToIgnoringGivenFields(
                TaskDto.TaskSummaryJson.builder()
                        .userId("lu92")
                        .name("task-1")
                        .description("task-1 description")
                        .comments(List.of())
                        .created(_01_01_2022)
                        .status(TaskStatus.OPEN)
                        .build(),
                "taskId"
        );

        TaskDto.TaskSummaryJson taskClosureSummary = taskRestController.close(
                TaskDto.CloseTaskJson.builder()
                        .taskId(taskCreationSummary.getTaskId())
                        .comment("some comment")
                        .build()
        );

        assertThat(taskClosureSummary).isEqualTo(
                TaskDto.TaskSummaryJson.builder()
                        .taskId(taskCreationSummary.getTaskId())
                        .userId("lu92")
                        .name("task-1")
                        .description("task-1 description")
                        .comments(List.of(TaskDto.CommentJson.builder().message("some comment").created(_01_01_2022).build()))
                        .status(TaskStatus.CLOSED)
                        .created(_01_01_2022)
                        .build());
        System.out.println(taskClosureSummary);
    }
}
