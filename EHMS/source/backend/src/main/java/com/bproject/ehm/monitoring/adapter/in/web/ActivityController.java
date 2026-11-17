package com.bproject.ehm.monitoring.adapter.in.web;

import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.Instant;

/** Passive scoped observation. Never starts, resets or reconfigures a background worker. */
@RestController @Profile("server") @RequestMapping("/api/ehm/v1/system/activity")
public class ActivityController {
    private final OpenGaussJsonStore store;
    public ActivityController(OpenGaussJsonStore store){this.store=store;}
    @GetMapping public Map<String,Object> status(){
        var rows=store.activity();String changed=rows.stream().map(r->r.get("lastChangedAt").toString()).max(String::compareTo).orElse("");
        return Map.of("observedAt",Instant.now().toString(),"lastChangedAt",changed,"metrics",rows,
            "scope","CURRENT_AUTHORIZED_PROJECT_REGION_SUBSYSTEM","readOnly",true,
            "message","后台采样独立运行；本接口只观察当前授权范围，不启动任务、不补造停机历史。");
    }
}
