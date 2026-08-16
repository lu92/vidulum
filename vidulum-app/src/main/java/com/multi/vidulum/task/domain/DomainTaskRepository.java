package com.multi.vidulum.task.domain;

import com.multi.vidulum.shared.ddd.DomainRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DomainTaskRepository extends DomainRepository<TaskId, Task> {

    Page<Task> findByStatus(TaskStatus status, Pageable pageable);

}
