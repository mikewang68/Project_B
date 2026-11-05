package com.bproject.safety.module.personnel.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Latest station health; a restart waits for fresh gateway reports. */
@Service
public class UwbStationService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.bproject.safety.config.ScsScopedReferenceData centralReferences;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long STALE_MS = 10_000;
    private final Map<String, Entry> stations = new ConcurrentHashMap<>();
    private final Clock clock;

    public UwbStationService(Clock clock) {
        this.clock = clock;
    }

    @org.springframework.transaction.annotation.Transactional
    public int apply(List<StationReport> reports) {
        if (reports == null || reports.isEmpty()) return 0;
        long now = clock.millis();
        int applied = 0;
        for (StationReport report : reports) {
            if (report == null || report.stationId() == null || report.stationId().isBlank()) continue;
            String id = report.stationId().trim();
            if(com.bproject.safety.config.ScsMachineRegistry.current()!=null&&(centralReferences==null||!centralReferences.scopedMachineIngestAllowed(com.bproject.safety.config.ScsCentralScope.Root.RUNTIME,"UWB:"+id)))continue;
            if(com.bproject.safety.config.ScsCentralScope.legacyMachineIngress()
                    &&(centralReferences==null||!centralReferences.legacyIngestAllowed(com.bproject.safety.config.ScsCentralScope.Root.RUNTIME,"UWB:"+id)))continue;
            StationView view = new StationView(id, !Boolean.FALSE.equals(report.online()),
                    !Boolean.FALSE.equals(report.clockSynced()), report.snrDb() == null ? 0 : report.snrDb(),
                    !Boolean.FALSE.equals(report.powerOk()), report.tagCount() == null ? 0 : report.tagCount(),
                    OffsetDateTime.now(clock.withZone(ZONE)).toString());
            stations.put(id, new Entry(view, now));
            applied++;
        }
        return applied;
    }

    public List<StationView> list() {
        long now = clock.millis();
        var result=stations.values().stream().map(entry -> now - entry.atMillis() > STALE_MS
                ? new StationView(entry.view().stationId(), false, entry.view().clockSynced(),
                    entry.view().snrDb(), entry.view().powerOk(), entry.view().tagCount(), entry.view().lastSeen())
                : entry.view()).sorted(Comparator.comparing(StationView::stationId)).toList();
        if(com.bproject.safety.config.ScsCentralScope.current()!=null)
            return centralReferences==null?List.of():centralReferences.visible(com.bproject.safety.config.ScsCentralScope.Root.RUNTIME,result,v->"UWB:"+v.stationId());
        return result;
    }

    public StationSummary summary() {
        var all = list();
        var online = all.stream().filter(StationView::online).toList();
        Integer minimum = online.stream().map(StationView::snrDb).min(Integer::compareTo).orElse(null);
        return new StationSummary(all.size(), online.size(), minimum,
                !online.isEmpty() && online.stream().allMatch(StationView::clockSynced));
    }

    public record StationReport(String stationId, Boolean online, Boolean clockSynced,
                                Integer snrDb, Boolean powerOk, Integer tagCount) {}
    public record StationView(String stationId, boolean online, boolean clockSynced,
                              int snrDb, boolean powerOk, int tagCount, String lastSeen) {}
    public record StationSummary(int total, int online, Integer minSnrDb, boolean allClockSynced) {}
    private record Entry(StationView view, long atMillis) {}
}
