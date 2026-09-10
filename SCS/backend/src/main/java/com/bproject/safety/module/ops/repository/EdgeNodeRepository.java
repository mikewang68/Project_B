package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.DemoEdgeNode;
import java.util.List;
import java.util.Optional;

/** 边缘节点存储抽象；本地实现为 {@link InMemoryEdgeNodeRepository}，服务器阶段可替换 openGauss。 */
public interface EdgeNodeRepository {

    List<DemoEdgeNode> findAll();

    Optional<DemoEdgeNode> findById(String id);

    DemoEdgeNode save(DemoEdgeNode node);

    long count();
}
