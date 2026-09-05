package com.bproject.safety.module.collision.repository;

import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import java.util.List;
import java.util.Optional;

/** 防碰撞设备存储抽象（本地 InMemory 实现）。 */
public interface CollisionRepository {

    List<DemoCollisionDevice> findAll();

    Optional<DemoCollisionDevice> findById(String id);

    DemoCollisionDevice save(DemoCollisionDevice device);
}
