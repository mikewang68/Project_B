package com.bdemo.sys.log;

import com.bdemo.sys.common.PageResult;
import com.bdemo.sys.common.R;
import com.bdemo.sys.common.WebUtils;
import com.bdemo.sys.log.domain.SysLog;
import com.bdemo.sys.security.JwtAuthFilter;
import com.bdemo.sys.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/logs")
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    @GetMapping
    @RequirePerm("sys:log:list:view")
    public R<PageResult<SysLog>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String begin,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return R.ok(logService.query(kind, module, result, keyword, begin, end, pageNum, pageSize));
    }

    @GetMapping("/export")
    @RequirePerm("sys:log:list:export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String begin,
            @RequestParam(required = false) String end,
            HttpServletRequest http) {
        byte[] csv = logService.exportCsv(kind, module, result, keyword, begin, end);
        // 自记一条导出日志
        logService.record(JwtAuthFilter.currentUser(), "operation", "log", "export",
                "操作日志", "导出日志 CSV（" + filterDesc(kind, module, result, keyword) + "）",
                http.getMethod(), http.getRequestURI(), WebUtils.clientIp(http), "success");
        String filename = URLEncoder.encode("系统日志_" + LocalDate.now() + ".csv", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    private String filterDesc(String kind, String module, String result, String keyword) {
        StringBuilder sb = new StringBuilder();
        if (kind != null && !kind.isBlank()) sb.append("类别=").append(kind).append(' ');
        if (module != null && !module.isBlank()) sb.append("模块=").append(module).append(' ');
        if (result != null && !result.isBlank()) sb.append("结果=").append(result).append(' ');
        if (keyword != null && !keyword.isBlank()) sb.append("关键词=").append(keyword);
        return sb.toString().trim();
    }
}
