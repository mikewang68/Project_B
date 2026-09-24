package com.bproject.ehm.workbench.ports;

import com.bproject.ehm.workbench.domain.model.UserTask;
import java.util.List;
import java.util.Optional;

public interface UserTaskRepository {
    Optional<UserTask> findByTaskNo(String taskNo);
    List<UserTask> findAll();
    UserTask save(UserTask task);
    long count();
}
