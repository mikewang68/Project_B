package com.bproject.ehm.platform.persistence.opengauss;

import com.bproject.ehm.workbench.domain.model.ShiftHandover;
import com.bproject.ehm.workbench.domain.model.UserTask;
import com.bproject.ehm.workbench.ports.ShiftHandoverRepository;
import com.bproject.ehm.workbench.ports.UserTaskRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("server")
class OpenGaussUserTaskRepository extends OpenGaussAdapterSupport implements UserTaskRepository {
    private static final String TYPE = "user-task";
    OpenGaussUserTaskRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<UserTask> findByTaskNo(String no) { return store.find(TYPE, no, UserTask.class); }
    public List<UserTask> findAll() { return store.findAll(TYPE, UserTask.class).stream()
            .sorted(Comparator.comparing(UserTask::updatedAt, newestFirst())).toList(); }
    public UserTask save(UserTask value) { return store.save(TYPE, value.taskNo(), value); }
    public long count() { return store.count(TYPE); }
}

@Repository
@Profile("server")
class OpenGaussShiftHandoverRepository extends OpenGaussAdapterSupport implements ShiftHandoverRepository {
    private static final String TYPE = "shift-handover";
    OpenGaussShiftHandoverRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<ShiftHandover> findByHandoverNo(String no) { return store.find(TYPE, no, ShiftHandover.class); }
    public List<ShiftHandover> findAll() { return store.findAll(TYPE, ShiftHandover.class).stream()
            .sorted(Comparator.comparing(ShiftHandover::updatedAt, newestFirst())).toList(); }
    public ShiftHandover save(ShiftHandover value) { return store.save(TYPE, value.handoverNo(), value); }
    public long count() { return store.count(TYPE); }
}
