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

    /**
     * 复查-D1：待评审草稿。已生效围栏编辑进入 TO_REVIEW 后，生效（governing）版本保持
     * 运行不变，新几何存为草稿版本；本方法返回草稿全量视图（无草稿时 empty）。
     * list/findById 返回的始终是生效（运行中）版本。
     */
    Optional<DemoFence> findDraft(String id);
}
