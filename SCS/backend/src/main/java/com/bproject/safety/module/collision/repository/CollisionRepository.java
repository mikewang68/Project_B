package com.bproject.safety.module.collision.repository;

import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import java.util.List;
import java.util.Optional;

/** 防碰撞设备存储抽象（本地 InMemory 实现）。 */
public interface CollisionRepository {

    List<DemoCollisionDevice> findAll();

    Optional<DemoCollisionDevice> findById(String id);

    /**
     * 无范围读取：仅供已通过绑定对象校验的命名机器来源补齐 legacy 台账行
     * （如配对设备的历史行尚未盖范围戳）。只允许无范围行或当前机器元组内的行，
     * 防止配错对象清单时读到其它项目/区域的设备。普通业务读取一律使用 {@link #findById(String)}。
     */
    default Optional<DemoCollisionDevice> findByIdUnscoped(String id, com.bproject.iam.context.ScopeTriple scope) {
        return findById(id);
    }

    DemoCollisionDevice save(DemoCollisionDevice device);
}
