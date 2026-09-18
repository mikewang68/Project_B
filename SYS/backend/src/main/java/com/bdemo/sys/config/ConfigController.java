package com.bdemo.sys.config;

import com.bdemo.sys.common.R;
import com.bdemo.sys.common.WebUtils;
import com.bdemo.sys.config.domain.SysConfigItem;
import com.bdemo.sys.config.dto.ConfigGroupRequest;
import com.bdemo.sys.log.LogService;
import com.bdemo.sys.security.JwtAuthFilter;
import com.bdemo.sys.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/configs")
public class ConfigController {

    private final ConfigService configService;
    private final LogService logService;

    public ConfigController(ConfigService configService, LogService logService) {
        this.configService = configService;
        this.logService = logService;
    }

    @GetMapping
    @RequirePerm("sys:config:list:view")
    public R<List<SysConfigItem>> list() {
        return R.ok(configService.list());
    }

    @PutMapping("/group/{group}")
    @RequirePerm("sys:config:list:edit")
    public R<Map<String, Integer>> saveGroup(@PathVariable String group,
                                             @Valid @RequestBody ConfigGroupRequest request,
                                             HttpServletRequest http) {
        int changed = configService.saveGroup(group, request);
        if (changed > 0) {
            logService.record(JwtAuthFilter.currentUser(), "operation", "config", "edit",
                    "系统配置", "保存「" + group + "」配置，更新 " + changed + " 项参数",
                    http.getMethod(), http.getRequestURI(), WebUtils.clientIp(http), "success");
        }
        return R.ok(Map.of("updated", changed));
    }
}
