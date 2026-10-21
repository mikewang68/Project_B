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

    record PersonnelStats(int online, int abnormal, int bandOffline, int lowBattery) {
    }

    default PersonnelStats getStats() {
        List<DemoPersonnel> all = findAll();
        long online = all.stream().filter(p -> com.bproject.safety.module.personnel.model.PersonStatuses.ONLINE.equals(p.statusCode)).count();
        long abnormal = all.stream().filter(p -> !com.bproject.safety.module.personnel.model.PersonRiskLevels.NORMAL.equals(p.riskCode)).count();
        long bandOffline = all.stream().filter(p -> com.bproject.safety.module.personnel.model.BraceletStatuses.OFFLINE.equals(p.braceletStatusCode)).count();
        long lowBattery = all.stream()
                .filter(p -> com.bproject.safety.module.personnel.model.BraceletStatuses.LOW_BATTERY.equals(p.braceletStatusCode) || p.battery < 20).count();
        return new PersonnelStats((int) online, (int) abnormal, (int) bandOffline, (int) lowBattery);
    }
}
