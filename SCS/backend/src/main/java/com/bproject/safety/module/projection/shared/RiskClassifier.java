package com.bproject.safety.module.projection.shared;

import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风险分类统一工具：统计分析 / 首页 Projection / 大屏共享同一套“事件大类 / 高风险 / 对象提取”口径，
 * 避免各模块各写一套 if/switch。
 *
 * <p>Backend Demo 阶段告警的 eventType 为细分名（如“危险区域闯入”），分析图表使用归并后的大类
 * （如“人员越界”）；正式领域设计时应以统一的事件类型字典替换此映射。</p>
 */
public final class RiskClassifier {

    private RiskClassifier() {
    }

    private static final Pattern PERSON = Pattern.compile("P-\\d+");
    private static final Pattern DEVICE = Pattern.compile("[A-Z]+(?:-[A-Z]+)*-\\d+");

    /** 高风险 = 严重 / 紧急（入参可为机器 code 或中文标签，统一归一后判断）。 */
    public static boolean isHighRisk(String riskCodeOrLabel) {
        return RiskLevels.isHigh(RiskLevels.normalize(riskCodeOrLabel));
    }

    public static boolean isHighRisk(DemoAlert a) {
        return RiskLevels.isHigh(a.riskCode);
    }

    /** 归并为统计分析使用的事件大类（与前端 analyticsData 的类型口径对齐）。 */
    public static String analyticsType(DemoAlert a) {
        String t = a.eventType == null ? a.title : a.eventType;
        if (t == null) {
            return "其他";
        }
        if (t.contains("闯入") || t.contains("越界") || t.contains("围栏")) {
            return "人员越界";
        }
        if (t.contains("滞留") || t.contains("电量") || t.contains("穿戴") || t.contains("信号")) {
            return "人员异常";
        }
        if (t.contains("距离") || t.contains("交汇") || t.contains("接近")) {
            return "设备距离风险";
        }
        if (t.contains("安全帽")) {
            return "未佩戴安全帽";
        }
        if (t.contains("翻越")) {
            return "翻越护栏";
        }
        if (t.contains("视频") || t.contains("摄像头")) {
            return "视频设备异常";
        }
        if (t.contains("设备") || t.contains("机械") || t.contains("油压") || t.contains("资源") || t.contains("磁盘")) {
            return "设备异常";
        }
        return t;
    }

    /** 提取人员编号 P-xxxx（不存在返回 null）。 */
    public static String personIdOf(String target) {
        if (target == null) {
            return null;
        }
        Matcher m = PERSON.matcher(target);
        return m.find() ? m.group() : null;
    }

    /** 提取首个设备编号（如 G-CRANE-01 / VEH-08 / CAM-02，不存在返回 null）。
     *  注意：人员编号 P-xxxx 也能被设备正则匹配，必须显式跳过，避免人员混入高频设备。 */
    public static String deviceIdOf(String target) {
        if (target == null) {
            return null;
        }
        Matcher m = DEVICE.matcher(target);
        while (m.find()) {
            String id = m.group();
            if (id != null && !id.startsWith("P-")) {
                return id;
            }
        }
        return null;
    }

    /** 人员显示名：优先中文名，其次保留“作业人员 / 外协人员 + 编号”。 */
    public static String personNameOf(String target, String personId) {
        if (target == null) {
            return personId;
        }
        String name = target.replaceAll("（.*?）|\\(.*?\\)", "").trim();
        if (name.contains("作业人员") || name.contains("外协人员") || name.isBlank()) {
            return target.trim();
        }
        return name;
    }

    /** 设备编号 → 设备类别名（DEMO 台账命名）。 */
    public static String deviceNameOf(String deviceId) {
        if (deviceId == null) {
            return "未知设备";
        }
        if (deviceId.startsWith("VEH")) {
            return "转运车辆 " + deviceId.substring(deviceId.lastIndexOf('-') + 1);
        }
        if (deviceId.startsWith("G-CRANE")) {
            return "龙门吊 " + deviceId.substring(deviceId.lastIndexOf('-') + 1);
        }
        if (deviceId.startsWith("TIP")) {
            return "翻箱机 " + deviceId.substring(deviceId.lastIndexOf('-') + 1);
        }
        if (deviceId.startsWith("CAM")) {
            return "摄像头 " + deviceId;
        }
        if (deviceId.startsWith("EDGE")) {
            return "边缘节点 " + deviceId;
        }
        return deviceId;
    }

    /**
     * DEMO 班组映射：Alert 暂无班组字段，按对象 / 责任人归并到演示班组；
     * 班组名称统一使用 DemoMasterData 的 canonical 名称（安全管理组 / 设备维保班），
     * 正式领域模型补齐人员-班组关系后移除。
     */
    public static String teamOf(DemoAlert a) {
        String t = a.target == null ? "" : a.target;
        if (t.contains("外协")) {
            return "外协单位";
        }
        String pid = personIdOf(t);
        if (pid != null) {
            return switch (pid) {
                case "P-1021", "P-1034" -> "装卸二班";
                default -> "装卸一班";
            };
        }
        String dev = deviceIdOf(t);
        if (dev != null) {
            return "设备维保班";
        }
        if (a.assignee != null) {
            if (a.assignee.contains("李娜")) {
                return "安全管理组";
            }
            if (a.assignee.contains("刘志明")) {
                return "装卸二班";
            }
            if (a.assignee.contains("王建国")) {
                return "装卸一班";
            }
        }
        return "安全管理组";
    }
}
