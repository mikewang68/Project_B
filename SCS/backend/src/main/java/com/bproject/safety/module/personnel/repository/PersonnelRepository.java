package com.bproject.safety.module.personnel.repository;

import com.bproject.safety.module.personnel.model.DemoPersonnel;
import java.util.List;
import java.util.Optional;

/**
 * 人员存储抽象。本地实现为 {@link InMemoryPersonnelRepository}（无需数据库即可运行）；
 * 后续服务器阶段可替换为 openGauss 实现，Service 不感知存储细节。
 */
public interface PersonnelRepository {

    List<DemoPersonnel> findAll();

    Optional<DemoPersonnel> findById(String id);

    DemoPersonnel save(DemoPersonnel person);
}
