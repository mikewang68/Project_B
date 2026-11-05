package com.mt.wms.stockin;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/psms/inbound-tasks")
class PsmsMachineController {
    private final PsmsInboundService service;
    PsmsMachineController(PsmsInboundService service){this.service=service;}
    @PostMapping ResponseEntity<PsmsInboundModels.Accepted> accept(@Valid @RequestBody PsmsInboundModels.TaskRequest request){return ResponseEntity.accepted().body(service.accept(request));}
    @GetMapping("/{taskId}") PsmsInboundModels.TaskView get(@PathVariable String taskId){return service.machineGet(taskId);}
}
