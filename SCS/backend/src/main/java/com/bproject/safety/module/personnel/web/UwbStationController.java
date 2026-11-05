package com.bproject.safety.module.personnel.web;

import com.bproject.safety.module.personnel.service.UwbStationService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/personnel")
public class UwbStationController {
    private final UwbStationService service;

    public UwbStationController(UwbStationService service) {
        this.service = service;
    }

    @GetMapping("/uwb-stations")
    public StationResponse stations() {
        return new StationResponse(service.summary(), service.list());
    }

    public record StationResponse(UwbStationService.StationSummary summary,
                                  List<UwbStationService.StationView> stations) {}
}
