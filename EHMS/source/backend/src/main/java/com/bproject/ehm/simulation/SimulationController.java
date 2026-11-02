package com.bproject.ehm.simulation;

import com.bproject.ehm.platform.security.BusinessPermissionFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController @Profile("server") @RequestMapping("/api/ehm/v1/system/simulation")
public class SimulationController {
    private final ObjectProvider<SimulationService> services;
    public SimulationController(ObjectProvider<SimulationService> services){this.services=services;}
    private SimulationService service(){
        var s=services.getIfAvailable();
        if(s==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"模拟运行未启用，正式服务默认关闭");
        if(!s.currentProjectAllowed())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前不是已授权的模拟项目");
        return s;
    }
    private SimulationService admin(HttpServletRequest request){
        if(!BusinessPermissionFilter.admin(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅超级管理员可以控制模拟运行");
        return service();
    }
    @GetMapping public Map<String,Object> status(){return services.getIfAvailable()==null?Map.of("enabled",false):service().status();}
    public record Command(String confirmation,String requestId){}
    public record Configure(String confirmation,SimulationService.Settings settings){}
    @PostMapping("/install") public Map<String,Object> install(@RequestBody Command body,HttpServletRequest request){return admin(request).install(body.confirmation());}
    @PostMapping("/start") public Map<String,Object> start(@RequestBody Command body,HttpServletRequest request){return admin(request).running(true,body.confirmation());}
    @PostMapping("/pause") public Map<String,Object> pause(@RequestBody Command body,HttpServletRequest request){return admin(request).running(false,body.confirmation());}
    @PostMapping("/tick") public Map<String,Object> tick(@RequestBody Command body,HttpServletRequest request){return admin(request).tick(body.requestId(),body.confirmation());}
    @PostMapping("/settings") public Map<String,Object> configure(@RequestBody Configure body,HttpServletRequest request){
        if(body.settings()==null)throw new IllegalArgumentException("缺少运行参数");
        return admin(request).configure(body.settings(),body.confirmation());
    }
}
