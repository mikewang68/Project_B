package com.bproject.safety.module.fence.repository;

import com.bproject.safety.module.fence.model.DemoFence;
import java.util.List;
import java.util.Optional;

/** 围栏存储抽象（本地 InMemory 实现，后续可替换 openGauss 实现）。 */
public interface FenceRepository {

    List<DemoFence> findAll();

    Optional<DemoFence> findById(String id);

    DemoFence save(DemoFence fence);

    long count();
}
