package com.bdemo.sys.diagnosis;

import com.bdemo.sys.common.BizException;
import com.bdemo.sys.log.domain.SysLog;
import com.bdemo.sys.log.mapper.LogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SYS Log Diagnosis Agent —— 纯规则驱动，不调用任何 LLM。
 * 基于真实操作日志做失败率 / 权限拒绝 / 模块与用户集中 / 短时重复等诊断，
 * 输出结构化、可解释、可下钻到证据日志的发现；只分析建议，不执行写操作（Human-in-the-loop）。
 */
@Service
public class LogDiagnosisService {

    // ---- 阈值常量（集中定义，不散落）----
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 30;
    private static final double HIGH_FAILURE_RATE = 0.20;
    private static final int MIN_TOTAL_HIGH_RATE = 10;
    private static final int PERM_WINDOW_MINUTES = 10;
    private static final int PERM_DENY_MIN_COUNT = 3;
    private static final double MODULE_FAIL_SHARE = 0.60;
    private static final int MODULE_FAIL_MIN = 3;
    private static final double USER_FAIL_SHARE = 0.50;
    private static final int USER_FAIL_MIN = 3;
    private static final int REPEAT_WINDOW_SECONDS = 120;
    private static final int REPEAT_MIN_COUNT = 3;
    private static final int EVIDENCE_LIMIT = 20;

    private static final List<String> DENY_MARKERS =
            List.of("403", "权限", "禁止", "forbidden", "denied", "拒绝");

    private final LogMapper mapper;

    public LogDiagnosisService(LogMapper mapper) {
        this.mapper = mapper;
    }

    public LogDiagnosisReport diagnose(String from, String to, String user, String module, String result) {
        LocalDate today = LocalDate.now();
        LocalDate fromDate = blank(from) ? today.minusDays(DEFAULT_DAYS - 1L) : parseDate(from);
        LocalDate toDate = blank(to) ? today : parseDate(to);
        if (toDate.isBefore(fromDate)) {
            throw BizException.badRequest("结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(fromDate, toDate) > MAX_DAYS) {
            throw BizException.badRequest("诊断时间范围不能超过 30 天");
        }
        LocalDateTime begin = fromDate.atStartOfDay();
        LocalDateTime end = toDate.atTime(23, 59, 59);

        List<SysLog> logs = mapper.selectForDiagnosis(begin, end,
                blank(user) ? null : user.trim(),
                blank(module) ? null : module.trim(),
                blank(result) ? null : result.trim());

        long total = logs.size();
        long failed = logs.stream().filter(l -> "fail".equals(l.getResult())).count();
        long success = total - failed;

        List<LogDiagnosisFinding> findings = new ArrayList<>();
        ruleHighFailureRate(logs, total, failed, findings);
        rulePermissionDenied(logs, findings);
        ruleModuleConcentration(logs, failed, findings);
        ruleUserConcentration(logs, failed, findings);
        ruleRepeatedFailure(logs, findings);

        if (failed == 0) {
            findings.add(new LogDiagnosisFinding("SYS-LOG-006", "INFO", "全部正常",
                    "当前时间范围未发现明显异常。", List.of(), "无需处理。"));
        } else if (findings.isEmpty()) {
            findings.add(new LogDiagnosisFinding("SYS-LOG-006", "INFO", "未发现达到告警阈值的异常",
                    "当前时间范围存在 " + failed + " 条失败记录，但均未达到各规则的告警阈值。",
                    List.of(), "可持续观察，必要时缩小时间范围下钻。"));
        }

        findings.sort(Comparator.comparingInt(LogDiagnosisService::severityRank)
                .thenComparing(LogDiagnosisFinding::getRuleId));

        DiagnosisSummary summary = new DiagnosisSummary();
        summary.setTotal(total);
        summary.setSuccess(success);
        summary.setFailed(failed);
        summary.setFailureRate(round4(total == 0 ? 0 : failed * 1.0 / total));

        return new LogDiagnosisReport(
                new DiagnosisTimeRange(fromDate.toString(), toDate.toString()), summary, findings);
    }

    // ---- SYS-LOG-001 高失败率 ----
    private void ruleHighFailureRate(List<SysLog> logs, long total, long failed,
                                     List<LogDiagnosisFinding> findings) {
        if (total < MIN_TOTAL_HIGH_RATE) {
            return;
        }
        double rate = failed * 1.0 / total;
        if (rate < HIGH_FAILURE_RATE) {
            return;
        }
        List<String> ids = logs.stream()
                .filter(l -> "fail".equals(l.getResult()))
                .map(SysLog::getId).limit(EVIDENCE_LIMIT).toList();
        findings.add(new LogDiagnosisFinding(
                "SYS-LOG-001", "HIGH", "整体失败率偏高",
                "时间范围内共 " + total + " 条操作，失败 " + failed + " 条，失败率 "
                        + pct(rate) + "%，超过 " + pct(HIGH_FAILURE_RATE) + "% 阈值。",
                ids, "建议按模块、用户维度下钻，定位失败的主要来源。"));
    }

    // ---- SYS-LOG-002 连续权限拒绝 ----
    private void rulePermissionDenied(List<SysLog> logs, List<LogDiagnosisFinding> findings) {
        Map<String, List<SysLog>> byUser = new LinkedHashMap<>();
        for (SysLog l : logs) {
            if (isDeny(l)) {
                byUser.computeIfAbsent(nz(l.getUsername()), k -> new ArrayList<>()).add(l);
            }
        }
        byUser.forEach((u, list) -> {
            for (int end = 0; end < list.size(); end++) {
                LocalDateTime t = list.get(end).getCreatedAt();
                if (t == null) {
                    continue;
                }
                LocalDateTime windowStart = t.minusMinutes(PERM_WINDOW_MINUTES);
                List<SysLog> inWindow = new ArrayList<>();
                for (int k = 0; k <= end; k++) {
                    SysLog c = list.get(k);
                    if (c.getCreatedAt() != null && !c.getCreatedAt().isBefore(windowStart)
                            && !c.getCreatedAt().isAfter(t)) {
                        inWindow.add(c);
                    }
                }
                if (inWindow.size() >= PERM_DENY_MIN_COUNT) {
                    List<String> ids = inWindow.stream().map(SysLog::getId)
                            .limit(EVIDENCE_LIMIT).toList();
                    findings.add(new LogDiagnosisFinding(
                            "SYS-LOG-002", "MEDIUM", "用户连续权限拒绝",
                            "用户 " + u + " 在 " + PERM_WINDOW_MINUTES + " 分钟内出现 "
                                    + inWindow.size() + " 次权限拒绝，可能存在权限配置问题。",
                            ids, "检查 IAM 中该用户的角色与对应 permissionCode。"));
                    break;
                }
            }
        });
    }

    // ---- SYS-LOG-003 单模块失败集中 ----
    private void ruleModuleConcentration(List<SysLog> logs, long failed,
                                         List<LogDiagnosisFinding> findings) {
        if (failed <= 0) {
            return;
        }
        Map<String, List<SysLog>> byModule = new LinkedHashMap<>();
        for (SysLog l : logs) {
            if ("fail".equals(l.getResult())) {
                byModule.computeIfAbsent(nz(l.getModule()), k -> new ArrayList<>()).add(l);
            }
        }
        byModule.forEach((m, list) -> {
            if (list.size() >= MODULE_FAIL_MIN) {
                double share = list.size() * 1.0 / failed;
                if (share >= MODULE_FAIL_SHARE) {
                    List<String> ids = list.stream().map(SysLog::getId)
                            .limit(EVIDENCE_LIMIT).toList();
                    findings.add(new LogDiagnosisFinding(
                            "SYS-LOG-003", "MEDIUM", "失败集中于模块",
                            "模块 " + m + " 失败 " + list.size() + " 条，占全部失败的 "
                                    + pct(share) + "%。",
                            ids, "优先排查该模块的接口、权限与配置。"));
                }
            }
        });
    }

    // ---- SYS-LOG-004 单用户失败集中 ----
    private void ruleUserConcentration(List<SysLog> logs, long failed,
                                       List<LogDiagnosisFinding> findings) {
        if (failed <= 0) {
            return;
        }
        Map<String, List<SysLog>> byUser = new LinkedHashMap<>();
        for (SysLog l : logs) {
            if ("fail".equals(l.getResult())) {
                byUser.computeIfAbsent(nz(l.getUsername()), k -> new ArrayList<>()).add(l);
            }
        }
        byUser.forEach((u, list) -> {
            if (list.size() >= USER_FAIL_MIN) {
                double share = list.size() * 1.0 / failed;
                if (share >= USER_FAIL_SHARE) {
                    List<String> ids = list.stream().map(SysLog::getId)
                            .limit(EVIDENCE_LIMIT).toList();
                    findings.add(new LogDiagnosisFinding(
                            "SYS-LOG-004", "MEDIUM", "用户为失败主要来源",
                            "用户 " + u + " 失败 " + list.size() + " 条，占全部失败的 "
                                    + pct(share) + "%，为当前失败事件主要来源。",
                            ids, "检查该用户的角色、权限与操作流程。"));
                }
            }
        });
    }

    // ---- SYS-LOG-005 短时间重复相同失败（只能说"可能"）----
    private void ruleRepeatedFailure(List<SysLog> logs, List<LogDiagnosisFinding> findings) {
        Map<String, List<SysLog>> groups = new LinkedHashMap<>();
        for (SysLog l : logs) {
            if (!"fail".equals(l.getResult())) {
                continue;
            }
            String key = String.join("|", nz(l.getUsername()), nz(l.getModule()), nz(l.getAction()), "fail");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
        }
        groups.forEach((key, list) -> {
            for (int end = 0; end < list.size(); end++) {
                LocalDateTime t = list.get(end).getCreatedAt();
                if (t == null) {
                    continue;
                }
                LocalDateTime windowStart = t.minusSeconds(REPEAT_WINDOW_SECONDS);
                List<SysLog> inWindow = new ArrayList<>();
                for (int k = 0; k <= end; k++) {
                    SysLog c = list.get(k);
                    if (c.getCreatedAt() != null && !c.getCreatedAt().isBefore(windowStart)
                            && !c.getCreatedAt().isAfter(t)) {
                        inWindow.add(c);
                    }
                }
                if (inWindow.size() >= REPEAT_MIN_COUNT) {
                    SysLog sample = list.get(end);
                    List<String> ids = inWindow.stream().map(SysLog::getId)
                            .limit(EVIDENCE_LIMIT).toList();
                    findings.add(new LogDiagnosisFinding(
                            "SYS-LOG-005", "LOW", "可能存在短时间重复相同失败",
                            "用户 " + nz(sample.getUsername()) + " 在模块 " + nz(sample.getModule())
                                    + " 的「" + nz(sample.getAction()) + "」操作，"
                                    + (REPEAT_WINDOW_SECONDS / 60) + " 分钟内重复失败 "
                                    + inWindow.size() + " 次；可能是重复点击、错误重试、前端循环请求或权限配置问题。",
                            ids, "确认是否重复提交；如非预期，检查前端请求逻辑与该操作的权限配置。"));
                    break;
                }
            }
        });
    }

    private boolean isDeny(SysLog l) {
        if (!"fail".equals(l.getResult())) {
            return false;
        }
        String hay = (nz(l.getDetail()) + " " + nz(l.getAction()) + " " + nz(l.getTarget())).toLowerCase();
        return DENY_MARKERS.stream().anyMatch(hay::contains);
    }

    private static int severityRank(LogDiagnosisFinding f) {
        return switch (f.getSeverity()) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 3;
        };
    }

    /** 0~1 比例转百分比文本数值（保留两位） */
    private static double pct(double share) {
        return Math.round(share * 10000) / 100.0;
    }

    private static double round4(double x) {
        return Math.round(x * 10000) / 10000.0;
    }

    private LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            throw BizException.badRequest("日期格式应为 yyyy-MM-dd");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
