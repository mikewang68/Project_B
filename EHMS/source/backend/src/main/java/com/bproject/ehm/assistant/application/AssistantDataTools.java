package com.bproject.ehm.assistant.application;

import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.asset.application.AssetGovernanceApplicationService;
import com.bproject.ehm.asset.application.DeviceView;
import com.bproject.ehm.alarm.application.AlarmQueryFacade;
import com.bproject.ehm.maintenance.application.WorkOrderQueryFacade;
import com.bproject.ehm.reliability.application.ReliabilityGovernanceApplicationService;
import com.bproject.ehm.workbench.application.WorkbenchApplicationService;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Explicit read-only tool catalog. No SQL, URL, shell, or write tools are exposed. */
@Service
public class AssistantDataTools {
    private static final List<String> CATEGORIES = List.of("devices", "alarms", "work_orders", "tasks", "handovers", "calibrations", "changes", "templates", "knowledge");
    private static final Map<String, String> PAGES = Map.of("devices", "fleet", "alarms", "alarm-center", "work_orders", "workorders", "tasks", "my-tasks", "handovers", "shift-handover", "calibrations", "sensor-cal", "changes", "config-change", "templates", "device-template", "knowledge", "knowledge");
    private static final Map<String, String> LABELS = Map.of("devices", "设备台账与当前健康快照", "alarms", "告警记录", "work_orders", "维修工单", "tasks", "待办记录", "handovers", "班组交接", "calibrations", "校准记录", "changes", "配置变更履历", "templates", "设备模板", "knowledge", "已审核知识案例");
    private final AssetQueryFacade assets;
    private final AlarmQueryFacade alarms;
    private final WorkOrderQueryFacade orders;
    private final WorkbenchApplicationService workbench;
    private final AssetGovernanceApplicationService governance;
    private final ReliabilityGovernanceApplicationService reliability;
    private final ObjectMapper mapper;

    public AssistantDataTools(AssetQueryFacade assets, AlarmQueryFacade alarms, WorkOrderQueryFacade orders,
                             WorkbenchApplicationService workbench, AssetGovernanceApplicationService governance,
                             ReliabilityGovernanceApplicationService reliability, ObjectMapper mapper) {
        this.assets=assets; this.alarms=alarms; this.orders=orders; this.workbench=workbench;
        this.governance=governance; this.reliability=reliability; this.mapper=mapper;
    }

    public List<DeviceView> devices() { return scan(q -> assets.list(q, null, null, null)).rows(); }
    public List<Map<String, Object>> definitions() {
        return List.of(Map.of("type", "function", "function", Map.of(
                "name", "query_ehm", "description", "读取EHM数据库，返回脱敏业务数据和本地来源编号。需要当前系统事实时必须调用。无写入能力。最多返回30条，截断会标记。",
                "parameters", Map.of("type", "object", "properties", Map.of(
                    "category", Map.of("type", "string", "enum", CATEGORIES),
                    "device_code", Map.of("type", "string", "description", "设备别名DEV_001等；空表示不限设备"),
                    "keyword", Map.of("type", "string", "description", "按名称、故障、部件等关键词过滤；可为空"),
                    "status", Map.of("type", "string", "description", "按状态过滤；可为空"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 30)),
                    "required", List.of("category"), "additionalProperties", false))));
    }

    public QueryResult query(String name, String arguments, AssistantPrivacy privacy, int sourceNumber) {
        if (!"query_ehm".equals(name)) return error("不允许的工具；只支持query_ehm");
        try {
            JsonNode args = mapper.readTree(arguments);
            if (args == null || !args.isObject()) return error("工具参数必须是JSON对象");
            String category = args.path("category").asText();
            if (!CATEGORIES.contains(category)) return error("未知业务类别");
            String code = privacy.resolveCode(args.path("device_code").asText(""));
            String keyword = args.path("keyword").asText("").trim().toLowerCase(Locale.ROOT);
            String status = args.path("status").asText("").trim();
            if (keyword.length() > 80 || code.length() > 80 || status.length() > 80) return error("过滤条件过长");
            int limit = Math.min(30, Math.max(1, args.path("limit").asInt(12)));
            DataSet<?> set = switch (category) {
                case "devices" -> scan(q -> assets.list(q, null, null, null));
                case "alarms" -> scan(alarms::list);
                case "work_orders" -> scan(orders::list);
                case "tasks" -> full(workbench.listTasks(null, null));
                case "handovers" -> full(workbench.listHandovers(null));
                case "calibrations" -> full(governance.calibrations(null, null, null));
                case "changes" -> full(governance.changes(null, null));
                case "templates" -> full(governance.templates(null));
                case "knowledge" -> full(reliability.knowledgeCases().stream().filter(k -> "VERIFIED".equals(k.status())).toList());
                default -> throw new IllegalArgumentException();
            };
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Object row : set.rows()) {
                Map<String, Object> all = mapper.convertValue(row, new TypeReference<Map<String, Object>>() {});
                String asset = String.valueOf(all.getOrDefault("assetCode", all.getOrDefault("deviceCode", all.getOrDefault("code", ""))));
                if (!code.isBlank() && !code.equalsIgnoreCase(asset)) continue;
                if (!status.isBlank() && !String.valueOf(all.getOrDefault("status", "")).contains(status)) continue;
                Map<String, Object> safe = select(category, all);
                if (!keyword.isBlank() && !mapper.writeValueAsString(safe).toLowerCase(Locale.ROOT).contains(keyword)) continue;
                matches.add(safe);
            }
            if (category.equals("devices")) matches.sort(Comparator.comparingInt(m -> m.get("health") instanceof Number n ? n.intValue() : 101));
            String ref = "S" + sourceNumber;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", ref); data.put("category", category); data.put("queriedAt", Instant.now().toString());
            data.put("scannedRows", set.rows().size()); data.put("totalCategoryRows", set.total());
            data.put("projection", "白名单字段摘录，单个文本最多600字，列表最多12项");
            data.put("matchedInScannedRows", matches.size()); data.put("truncated", set.truncated() || matches.size() > limit);
            data.put("records", matches.stream().limit(limit).toList());
            if (set.truncated()) data.put("scopeNote", "仅检索前1000条；不能将匹配数解释为全库总数");
            Source source = new Source(ref, LABELS.get(category), PAGES.get(category), matches.size(), Instant.now());
            return new QueryResult(privacy.outgoing(mapper.writeValueAsString(data)), data, source);
        } catch (Exception ex) { return error("本次数据检索失败；请检查筛选条件或数据库连接。不得据此认定没有记录。"); }
    }

    public Map<String, Object> summary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("devices", assets.metrics()); data.put("alarms", alarms.metrics()); data.put("workOrders", orders.metrics());
        data.put("queriedAt", Instant.now().toString()); return data;
    }

    private Map<String, Object> select(String category, Map<String, Object> row) {
        String fields = switch (category) {
            case "devices" -> "code,name,type,condition,health,risk,riskClass,quality,ready,alarm,maintenanceDate,temperature,vibration,current,updatedAt";
            case "alarms" -> "deviceCode,component,level,summary,status,triggerMethod,occurredAt,closedAt";
            case "work_orders" -> "deviceCode,title,priority,status,source,description,plannedWindow,updatedAt";
            case "tasks" -> "assetCode,taskType,title,description,priority,status,dueAt,updatedAt";
            case "handovers" -> "shiftDate,outgoingShift,incomingShift,summary,riskItems,unfinishedItems,equipmentExceptions,status,updatedAt";
            case "calibrations" -> "assetCode,calibrationType,beforeValue,afterValue,tolerance,unit,result,calibratedAt,validUntil";
            case "changes" -> "assetCode,objectType,changeType,reason,impactAssessment,status,createdAt,appliedAt";
            case "templates" -> "deviceType,revision,status,inspectionPolicy,criticality";
            case "knowledge" -> "assetType,component,title,symptom,confirmedCause,diagnosisSteps,remedy,verificationCriterion,status";
            default -> "";
        };
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String field : fields.split(",")) if (row.containsKey(field)) safe.put(field, excerpt(row.get(field)));
        return safe;
    }
    private Object excerpt(Object value) {
        if (value instanceof String text) return text.length()>600 ? text.substring(0,600)+"[文本截断]" : text;
        if (value instanceof List<?> items) return items.stream().limit(12).map(this::excerpt).toList();
        return value;
    }
    private <T> DataSet<T> scan(Function<PageQuery, PageResult<T>> query) {
        List<T> rows = new ArrayList<>(); long total = 0;
        for (int page=0; page<5; page++) {
            PageResult<T> result = query.apply(new PageQuery(page, 200));
            total = result.totalElements(); rows.addAll(result.content());
            if (rows.size() >= total || result.content().isEmpty()) break;
        }
        return new DataSet<>(rows, total, rows.size() < total);
    }
    private DataSet<?> full(List<?> list) { return new DataSet<>(list.stream().limit(1000).toList(), list.size(), list.size()>1000); }
    private QueryResult error(String reason) { return new QueryResult("{\"error\":\""+reason+"\"}", Map.of("error", reason), null); }
    record DataSet<T>(List<T> rows, long total, boolean truncated) {}
    public record Source(String ref, String title, String page, int matchedRows, Instant queriedAt) {}
    public record QueryResult(String externalJson, Map<String, Object> localData, Source source) {}
}
