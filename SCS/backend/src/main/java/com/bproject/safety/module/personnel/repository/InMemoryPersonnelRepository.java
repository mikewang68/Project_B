package com.bproject.safety.module.personnel.repository;

import com.bproject.safety.module.personnel.model.DemoPersonnel;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 内存人员台账：7 名 Demo 人员，坐标与 frontend/src/views/PersonnelLocationView.vue 旧内联数据对齐，
 * 保证前端切换真实 API 后地图点位不发生明显跳变。
 */
@Repository
public class InMemoryPersonnelRepository implements PersonnelRepository {

    private final ConcurrentHashMap<String, DemoPersonnel> store = new ConcurrentHashMap<>();

    public InMemoryPersonnelRepository() {
        reset();
    }

    /** 重新灌入种子（测试隔离用）。 */
    public void reset() {
        store.clear();
        seed().forEach(p -> store.put(p.id, p.snapshotBaseline()));
    }

    private static List<DemoPersonnel> seed() {
        return java.util.List.of(
                person("P-ZHAO", "P-24018", "赵磊", "装卸一班", "装卸区 A", "WB-018", 78,
                        "在线", "在线", "优秀", "正常", "normal", 25, 30, "A-03 / 112.4, 86.7", 5.8, 0, "刚刚"),
                person("P-002", "P-23007", "李娜", "安全管理", "装卸区 A", "WB-007", 91,
                        "在线", "在线", "优秀", "正常", "normal", 17, 43, "A-08 / 96.2, 101.5", 4.2, 0, "3秒前"),
                person("P-003", "P-22032", "王强", "设备保障", "翻箱机作业区", "WB-032", 56,
                        "在线", "在线", "较低", "关注", "warning", 74, 69, "T-02 / 308.1, 75.6", 6.1, 1, "12秒前"),
                person("P-004", "V-10012", "陈晨", "外协单位", "装卸区 B", "WB-112", 84,
                        "在线", "在线", "良好", "高风险", "danger", 82, 34, "B-02 / 410.2, 95.1", 3.7, 2, "5秒前"),
                person("P-005", "P-24021", "孙磊", "装卸二班", "车辆通道", "WB-021", 16,
                        "在线", "低电量", "良好", "关注", "warning", 46, 52, "R-06 / 218.4, 158.2", 7.3, 1, "7秒前"),
                person("P-006", "P-21015", "周静", "质量管理", "装卸区 B", "WB-015", 73,
                        "在线", "在线", "优秀", "正常", "normal", 89, 46, "B-11 / 438.0, 122.5", 2.9, 0, "4秒前"),
                person("P-007", "P-23026", "吴昊", "设备保障", "龙门吊作业区", "WB-026", 0,
                        "离线", "离线", "无信号", "关注", "offline", 58, 19, "C-01 / 最后定位", 1.8, 1, "18分钟前"));
    }

    private static DemoPersonnel person(String id, String jobNo, String name, String team, String area,
                                        String band, int battery, String status, String bandStatus,
                                        String quality, String risk, String state, double x, double y,
                                        String coordinate, double distance, int alerts, String updated) {
        DemoPersonnel p = new DemoPersonnel();
        p.id = id;
        p.jobNo = jobNo;
        p.name = name;
        p.team = team;
        p.area = area;
        p.braceletId = band;
        p.battery = battery;
        p.status = status;
        p.braceletStatus = bandStatus;
        p.positioningQuality = quality;
        p.risk = risk;
        p.state = state;
        p.x = x;
        p.y = y;
        p.coordinate = coordinate;
        p.distanceToday = distance;
        p.alertsToday = alerts;
        p.lastUpdated = updated;
        return p;
    }

    @Override
    public List<DemoPersonnel> findAll() {
        return store.values().stream().sorted(Comparator.comparing(p -> p.id)).toList();
    }

    @Override
    public Optional<DemoPersonnel> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public DemoPersonnel save(DemoPersonnel person) {
        store.put(person.id, person);
        return person;
    }
}
