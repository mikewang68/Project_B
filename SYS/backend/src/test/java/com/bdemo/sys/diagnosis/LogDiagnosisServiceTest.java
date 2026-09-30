package com.bdemo.sys.diagnosis;

import com.bdemo.sys.log.domain.SysLog;
import com.bdemo.sys.log.mapper.LogMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LogDiagnosisService 纯单元测试：Mock Mapper + 内存 fixture，不依赖任何真实/生产数据，离线可运行。
 */
class LogDiagnosisServiceTest {

    private final LogMapper mapper = mock(LogMapper.class);
    private final LogDiagnosisService service = new LogDiagnosisService(mapper);

    private static final String FROM = "2026-09-01";
    private static final String TO = "2026-09-30";

    private LocalDateTime t(int day, int h, int mi) {
        return LocalDateTime.of(2026, 9, day, h, mi, 0);
    }

    private LocalDateTime t(int day, int h, int mi, int sec) {
        return LocalDateTime.of(2026, 9, day, h, mi, sec);
    }

    private SysLog log(String id, String username, String module, String action,
                       String result, String detail, LocalDateTime at) {
        SysLog l = new SysLog();
        l.setId(id);
        l.setUsername(username);
        l.setModule(module);
        l.setAction(action);
        l.setResult(result);
        l.setDetail(detail);
        l.setKind("operation");
        l.setCreatedAt(at);
        return l;
    }

    private SysLog fail(String id, String username, String module, String action,
                        String detail, LocalDateTime at) {
        return log(id, username, module, action, "fail", detail, at);
    }

    /** 生成 n 条互不相同（用户/模块/动作、时间递增）的成功日志 */
    private List<SysLog> successes(int n, int startDay) {
        String[] modules = {"dict", "config", "log", "user", "role", "auth"};
        List<SysLog> list = new ArrayList<>();
        LocalDateTime cur = t(startDay, 8, 0);
        for (int i = 0; i < n; i++) {
            list.add(log("s" + i, "su" + i, modules[i % modules.length], "list",
                    "success", "正常", cur));
            cur = cur.plusMinutes(3);
        }
        return list;
    }

    private void stub(List<SysLog> logs) {
        when(mapper.selectForDiagnosis(any(), any(), any(), any(), any())).thenReturn(logs);
    }

    private boolean hasRule(LogDiagnosisReport report, String ruleId) {
        return report.getFindings().stream().anyMatch(f -> ruleId.equals(f.getRuleId()));
    }

    private LogDiagnosisFinding first(LogDiagnosisReport report, String ruleId) {
        return report.getFindings().stream()
                .filter(f -> ruleId.equals(f.getRuleId())).findFirst().orElseThrow();
    }

    // ---------- 场景 ----------
    @Test
    void allSuccess_reportsNormal() {
        stub(successes(10, 1));
        LogDiagnosisReport report = service.diagnose(FROM, TO, null, null, null);
        LogDiagnosisFinding f = first(report, "SYS-LOG-006");
        assertEquals("INFO", f.getSeverity());
        assertTrue(report.getFindings().stream().noneMatch(x -> "HIGH".equals(x.getSeverity())));
    }

    @Test
    void highFailureRate_flaggedHigh() {
        List<SysLog> logs = successes(7, 1);
        logs.add(fail("f1", "fu1", "dict", "add", "新增失败", t(10, 9, 0)));
        logs.add(fail("f2", "fu2", "config", "edit", "编辑失败", t(15, 10, 0)));
        logs.add(fail("f3", "fu3", "log", "delete", "删除失败", t(20, 11, 0)));
        stub(logs);
        LogDiagnosisReport report = service.diagnose(FROM, TO, null, null, null);
        LogDiagnosisFinding f = first(report, "SYS-LOG-001");
        assertEquals("HIGH", f.getSeverity());
    }

    @Test
    void repeatedPermissionDenied_flagged() {
        List<SysLog> logs = successes(17, 1);
        logs.add(fail("d1", "bob", "user", "list", "403 权限不足", t(12, 9, 0)));
        logs.add(fail("d2", "bob", "role", "list", "权限被拒绝", t(12, 9, 2)));
        logs.add(fail("d3", "bob", "config", "list", "禁止访问", t(12, 9, 5)));
        stub(logs);
        LogDiagnosisReport report = service.diagnose(FROM, TO, null, null, null);
        LogDiagnosisFinding f = first(report, "SYS-LOG-002");
        assertEquals("MEDIUM", f.getSeverity());
        assertEquals(3, f.getEvidenceLogIds().size());
    }

    @Test
    void moduleFailureConcentration_flagged() {
        List<SysLog> logs = successes(17, 1);
        logs.add(fail("m1", "u1", "dict", "add", "新增失败", t(10, 9, 0)));
        logs.add(fail("m2", "u2", "dict", "edit", "编辑失败", t(11, 9, 0)));
        logs.add(fail("m3", "u3", "dict", "delete", "删除失败", t(12, 9, 0)));
        logs.add(fail("m4", "u4", "dict", "export", "导出失败", t(13, 9, 0)));
        stub(logs);
        LogDiagnosisReport report = service.diagnose(FROM, TO, null, null, null);
        LogDiagnosisFinding f = first(report, "SYS-LOG-003");
        assertEquals("MEDIUM", f.getSeverity());
    }

    @Test
    void userFailureConcentration_flagged() {
        List<SysLog> logs = successes(17, 1);
        logs.add(fail("c1", "carol", "dict", "add", "新增失败", t(10, 9, 0)));
        logs.add(fail("c2", "carol", "config", "edit", "编辑失败", t(11, 9, 0)));
        logs.add(fail("c3", "carol", "log", "delete", "删除失败", t(12, 9, 0)));
        logs.add(fail("c4", "carol", "role", "export", "导出失败", t(13, 9, 0)));
        stub(logs);
        LogDiagnosisReport report = service.diagnose(FROM, TO, null, null, null);
        LogDiagnosisFinding f = first(report, "SYS-LOG-004");
        assertEquals("MEDIUM", f.getSeverity());
    }

    @Test
    void repeatedSameFailure_flaggedLowAndHedged() {
        List<SysLog> logs = successes(17, 1);
        logs.add(fail("r1", "dave", "config", "edit", "保存失败", t(10, 9, 0)));
        logs.add(fail("r2", "dave", "config", "edit", "保存失败", t(10, 9, 0, 30)));
        logs.add(fail("r3", "dave", "config", "edit", "保存失败", t(10, 9, 1)));
        stub(logs);
        LogDiagnosisReport report = service.diagnose(FROM, TO, null, null, null);
        LogDiagnosisFinding f = first(report, "SYS-LOG-005");
        assertEquals("LOW", f.getSeverity());
        // 只能说"可能"，不武断根因
        assertTrue(f.getDescription().contains("可能"));
    }

    @Test
    void noLogs_reportsNormal() {
        stub(List.of());
        LogDiagnosisReport report = service.diagnose(FROM, TO, null, null, null);
        assertEquals(0, report.getSummary().getTotal());
        assertTrue(hasRule(report, "SYS-LOG-006"));
    }

    @Test
    void timeRange_boundaryEnforced() {
        stub(List.of());
        // 30 天间隔（含端点）合法，不抛异常
        service.diagnose("2026-09-01", "2026-10-01", null, null, null);
        // 31 天间隔被拒绝
        assertThrows(RuntimeException.class,
                () -> service.diagnose("2026-09-01", "2026-10-02", null, null, null));
    }
}
