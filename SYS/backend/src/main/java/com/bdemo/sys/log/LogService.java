package com.bdemo.sys.log;

import com.bdemo.sys.common.BizException;
import com.bdemo.sys.common.PageResult;
import com.bdemo.sys.log.domain.SysLog;
import com.bdemo.sys.log.mapper.LogMapper;
import com.bdemo.sys.security.LoginUser;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LogService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, String> MODULE_LABEL = Map.of(
            "auth", "登录认证", "dict", "数据字典", "config", "系统配置", "log", "日志管理",
            "user", "用户管理", "role", "角色管理");
    private static final Map<String, String> ACTION_LABEL = Map.ofEntries(
            Map.entry("login", "登录"), Map.entry("logout", "退出"),
            Map.entry("add", "新增"), Map.entry("edit", "编辑"),
            Map.entry("delete", "删除"), Map.entry("export", "导出"),
            Map.entry("status", "启停用"), Map.entry("resetPassword", "重置密码"),
            Map.entry("assignRoles", "分配角色"), Map.entry("assignPerms", "分配权限"));

    private final LogMapper mapper;

    public LogService(LogMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult<SysLog> query(String kind, String module, String result, String keyword,
                                    String begin, String end, Integer pageNum, Integer pageSize) {
        LocalDateTime beginTs = parseDayStart(begin);
        LocalDateTime endTs = parseDayEnd(end);
        String kw = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        if (pageNum == null || pageSize == null || pageSize <= 0) {
            List<SysLog> all = mapper.selectLogs(kind, module, result, keyword, kw,
                    beginTs, endTs, null, null);
            return new PageResult<>(all, all.size(), 1, all.size());
        }
        int pn = Math.max(pageNum, 1);
        int ps = Math.min(Math.max(pageSize, 1), 10000);
        long total = mapper.countLogs(kind, module, result, keyword, kw, beginTs, endTs);
        List<SysLog> list = mapper.selectLogs(kind, module, result, keyword, kw,
                beginTs, endTs, ps, (pn - 1) * ps);
        return new PageResult<>(list, total, pn, ps);
    }

    /** 按当前筛选条件导出 CSV（UTF-8 BOM，Excel 中文不乱码），列与前端原实现一致。 */
    public byte[] exportCsv(String kind, String module, String result, String keyword,
                            String begin, String end) {
        List<SysLog> rows = query(kind, module, result, keyword, begin, end, null, null).getList();
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("时间,类别,用户名,模块,动作,对象,详情,IP,结果\r\n");
        for (SysLog l : rows) {
            String[] cells = {
                    l.getCreatedAt() == null ? "" : TS.format(l.getCreatedAt()),
                    "login".equals(l.getKind()) ? "登录日志" : "操作日志",
                    nz(l.getUsername()),
                    MODULE_LABEL.getOrDefault(l.getModule(), nz(l.getModule())),
                    ACTION_LABEL.getOrDefault(l.getAction(), nz(l.getAction())),
                    nz(l.getTarget()), nz(l.getDetail()), nz(l.getIp()),
                    "success".equals(l.getResult()) ? "成功" : "失败",
            };
            sb.append(String.join(",", csv(cells))).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private String[] csv(String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            String v = cells[i] == null ? "" : cells[i];
            // 防 CSV 公式注入（CWE-1236）：以 = + - @ 或 Tab/回车/换行开头的内容，前置单引号使其按文本处理
            if (!v.isEmpty()) {
                char c = v.charAt(0);
                if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' || c == '\n') {
                    v = "'" + v;
                }
            }
            cells[i] = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return cells;
    }

    private LocalDateTime parseDayStart(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim()).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw BizException.badRequest("开始日期格式应为 yyyy-MM-dd");
        }
    }

    private LocalDateTime parseDayEnd(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim()).atTime(23, 59, 59);
        } catch (DateTimeParseException e) {
            throw BizException.badRequest("结束日期格式应为 yyyy-MM-dd");
        }
    }

    public void record(LoginUser user, String kind, String moduleName, String action,
                       String target, String detail, String method, String uri, String ip,
                       String result) {
        String username = user == null ? "anonymous" : user.getUsername();
        String userId = user == null ? null : user.getUserId();
        try {
            mapper.insert("l" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    kind, username, userId, moduleName, action, target, detail,
                    method, uri, ip, normalize(result), null, LocalDateTime.now());
        } catch (Exception e) {
            // 日志失败不阻断主业务
        }
    }

    private String normalize(String result) {
        return "fail".equalsIgnoreCase(result) ? "fail" : "success";
    }
}
