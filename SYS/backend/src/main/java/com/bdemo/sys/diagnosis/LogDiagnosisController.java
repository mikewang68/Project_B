package com.bdemo.sys.diagnosis;

import com.bdemo.sys.common.R;
import com.bdemo.sys.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日志智能诊断入口。具备日志查看权限即可运行（复用现有 permissionCode）。
 */
@RestController
@RequestMapping("/logs/diagnosis")
public class LogDiagnosisController {

    private final LogDiagnosisService logDiagnosisService;

    public LogDiagnosisController(LogDiagnosisService logDiagnosisService) {
        this.logDiagnosisService = logDiagnosisService;
    }

    @GetMapping
    @RequirePerm("sys:log:list:view")
    public R<LogDiagnosisReport> diagnose(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String result) {
        return R.ok(logDiagnosisService.diagnose(from, to, user, module, result));
    }
}
