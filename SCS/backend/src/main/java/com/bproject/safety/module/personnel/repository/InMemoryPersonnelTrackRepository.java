package com.bproject.safety.module.personnel.repository;

import com.bproject.safety.module.personnel.model.TrackPoint;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** 测试/本地 profile 的低频轨迹存储；生产使用 {@link JdbcPersonnelTrackRepository}。 */
@Repository
@Profile("!server")
public class InMemoryPersonnelTrackRepository implements PersonnelTrackRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private record Sample(double x, double y, String area, OffsetDateTime at) {
    }

    private final Map<String, List<Sample>> store = new ConcurrentHashMap<>();

    @Override
    public void append(String personnelCode, double x, double y, String area, OffsetDateTime recordedAt) {
        if (personnelCode == null || personnelCode.isBlank() || recordedAt == null) return;
        store.computeIfAbsent(personnelCode, key -> new CopyOnWriteArrayList<>())
                .add(new Sample(x, y, area, recordedAt));
    }

    @Override
    public List<TrackPoint> query(String personnelCode, OffsetDateTime from, OffsetDateTime to, int limit) {
        if (personnelCode == null || personnelCode.isBlank() || from == null || to == null) {
            return List.of();
        }
        int capped = Math.max(1, Math.min(limit, 10_000));
        return store.getOrDefault(personnelCode, List.of()).stream()
                .filter(sample -> !sample.at().isBefore(from) && !sample.at().isAfter(to))
                .sorted(Comparator.comparing(Sample::at))
                .limit(capped)
                .map(sample -> new TrackPoint(sample.x(), sample.y(),
                        sample.at().atZoneSameInstant(ZONE).format(HM)))
                .toList();
    }

    @Override
    public void clear() {
        store.clear();
    }
}
