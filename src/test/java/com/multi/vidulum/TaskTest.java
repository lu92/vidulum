package com.multi.vidulum;

import com.multi.vidulum.task.TaskDto;
import com.multi.vidulum.task.domain.TaskStatus;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TaskTest extends IntegrationTest {

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
                        .status(TaskStatus.OPEN)
                        .build(),
                "taskId", "created"
        );

        TaskDto.TaskSummaryJson taskClosureSummary = taskRestController.close(
                TaskDto.CloseTaskJson.builder()
                        .taskId(taskCreationSummary.getTaskId())
                        .comment("some comment")
                        .build()
        );

//        assertThat(taskClosureSummary).isEqualToIgnoringGivenFields(
//                TaskDto.TaskSummaryJson.builder().build(),
//                "created"
//        )
        System.out.println(taskClosureSummary);
    }
}
