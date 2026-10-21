package com.bproject.ehm.samples.adapter.in.web;

import com.bproject.ehm.samples.application.IndustrialSampleService;
import com.bproject.ehm.platform.security.BusinessPermissionFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @Profile("server") @RequestMapping("/api/ehm/v1/system/sample-data")
public class SampleDataController {
    private final IndustrialSampleService service;
    public SampleDataController(IndustrialSampleService service){this.service=service;}
    @GetMapping public Map<String,Object> status(){return service.status();}
    private void admin(HttpServletRequest request){if(!BusinessPermissionFilter.admin(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅超级管理员可管理虚拟样例");}
    @PostMapping("/import") public Map<String,Object> install(@RequestBody Confirmation body,HttpServletRequest request){admin(request);return service.install(body.bundle());}
    @PostMapping("/tick") public Map<String,Object> tick(@RequestBody Confirmation body,HttpServletRequest request){admin(request);return service.sampleNow(body.bundle());}
    public record Confirmation(String bundle){}
}
