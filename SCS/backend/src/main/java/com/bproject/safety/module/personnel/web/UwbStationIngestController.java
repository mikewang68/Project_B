package com.bproject.safety.module.personnel.web;

import com.bproject.safety.module.personnel.service.UwbStationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ingest")
public class UwbStationIngestController {
    private final UwbStationService service;

    public UwbStationIngestController(UwbStationService service) {
        this.service = service;
    }

    @PostMapping("/uwb-station")
    public Map<String, Object> uwbStation(@RequestBody(required = false) IngestRequest body) {
        var stations = body == null || body.stations() == null
                ? List.<UwbStationService.StationReport>of() : body.stations();
        int applied = service.apply(stations);
        List<String> ignored = new ArrayList<>();
        for (var station : stations) {
            if (station == null || station.stationId() == null || station.stationId().isBlank()) {
                ignored.add("<empty>");
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("applied", applied);
        response.put("ignored", ignored);
        response.put("source", body == null || body.source() == null ? "unknown" : body.source());
        return response;
    }

    public record IngestRequest(String source, List<UwbStationService.StationReport> stations) {}
}
