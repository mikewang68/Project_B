package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.AiBox;
import com.bproject.safety.module.ai.model.AiPageResult;
import com.bproject.safety.module.ai.model.AiReviewStatuses;
import com.bproject.safety.module.ai.model.AiRiskLevels;
import com.bproject.safety.module.ai.model.AiTimelineEventTypes;
import com.bproject.safety.module.ai.model.AiTimelineNode;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.alert.repository.JdbcAlertRepository;
import com.bproject.safety.support.demo.DemoClearableStore;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData.DemoUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 openGauss 的 AI 视觉识别事件持久化仓储实现。
 * 覆盖表结构：
 * <ul>
 *   <li>{@code safety.ai_event}（AI 识别主表、JSONB 检测框快照、关联 Alert UUID 外键）</li>
 *   <li>{@code safety.ai_event_timeline}（流转时间线、严格 sequence_no 保序）</li>
 * </ul>
 *
 * <p>遵循 openGauss 兼容规范：0 DDL 修改、精确时区转换、JSONB 类型适配、原子事务与 copy-on-write 语义。</p>
 */
@Repository
@Profile("server")
public class JdbcAiEventRepository implements AiEventRepository, DemoClearableStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcAiEventRepository.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String BASE_SELECT_ROOT = """
            SELECT e.id, e.event_no, e.event_type, e.camera_code, e.camera_name_snapshot, e.area_code,
                   e.confidence, e.duration_sec, e.model_code, e.threshold, e.status_code, e.risk_code,
                   e.camera_health_code, e.scene_type, e.detection_boxes, e.rule_code, e.related_person_code,
                   e.related_device_code, e.judge_text, e.reviewer_user_code, e.reviewer_name_snapshot,
                   e.review_time, e.false_reason, e.assignee_user_code, e.assignee_name_snapshot,
                   e.assignment_priority, e.process_status_code, e.assignment_note, e.linked_alert_id,
                   e.occurred_at, e.updated_at, e.created_at,
                   sa.alert_no AS linked_alert_no
            FROM safety.ai_event e
            LEFT JOIN safety.safety_alert sa ON e.linked_alert_id = sa.id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DemoMasterData masterData;
    private final Clock clock;

    public JdbcAiEventRepository(NamedParameterJdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper,
                                 DemoMasterData masterData,
                                 Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.masterData = masterData;
        this.clock = clock;
    }

    /**
     * 将业务 ID（如 AI-E-20260920-001）转为确定性 UUID；若已是有效 UUID 字符串则直接解析。
     */
    public static UUID resolveUuid(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(eventId.trim());
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(eventId.trim().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public List<DemoAiEvent> findAll() {
        String sql = BASE_SELECT_ROOT + " ORDER BY e.occurred_at DESC, e.id DESC";
        Map<UUID, DemoAiEvent> map = new LinkedHashMap<>();
        jdbcTemplate.query(sql, Collections.emptyMap(), rs -> {
            UUID id = (UUID) rs.getObject("id");
            map.put(id, mapEventRoot(rs));
        });

        loadTimelineChildren(map);
        return map.values().stream().map(DemoAiEvent::copy).toList();
    }

    @Override
    public List<DemoAiEvent> filter(AiEventQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder(BASE_SELECT_ROOT).append(" WHERE 1=1 ");
        buildQueryConditions(query, sql, params);
        sql.append(" ORDER BY e.occurred_at DESC, e.id DESC");

        Map<UUID, DemoAiEvent> map = new LinkedHashMap<>();
        jdbcTemplate.query(sql.toString(), params, rs -> {
            UUID id = (UUID) rs.getObject("id");
            map.put(id, mapEventRoot(rs));
        });

        loadTimelineChildren(map);
        return map.values().stream().map(DemoAiEvent::copy).toList();
    }

    @Override
    public AiPageResult page(AiEventQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM safety.ai_event e WHERE 1=1 ");
        buildQueryConditions(query, countSql, params);

        Long totalObj = jdbcTemplate.queryForObject(countSql.toString(), params, Long.class);
        int total = totalObj != null ? totalObj.intValue() : 0;

        if (total == 0) {
            return new AiPageResult(query.page(), query.pageSize(), 0, Collections.emptyList());
        }

        StringBuilder pageSql = new StringBuilder(BASE_SELECT_ROOT).append(" WHERE 1=1 ");
        buildQueryConditions(query, pageSql, params);
        pageSql.append(" ORDER BY e.occurred_at DESC, e.id DESC ");
        pageSql.append(" LIMIT :limit OFFSET :offset ");
        params.addValue("limit", query.pageSize());
        params.addValue("offset", (query.page() - 1) * query.pageSize());

        Map<UUID, DemoAiEvent> map = new LinkedHashMap<>();
        jdbcTemplate.query(pageSql.toString(), params, rs -> {
            UUID id = (UUID) rs.getObject("id");
            map.put(id, mapEventRoot(rs));
        });

        loadTimelineChildren(map);
        List<DemoAiEvent> list = map.values().stream().map(DemoAiEvent::copy).toList();
        return new AiPageResult(query.page(), query.pageSize(), total, list);
    }

    @Override
    public Optional<DemoAiEvent> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String sql = BASE_SELECT_ROOT + " WHERE e.id = :uuid OR e.event_no = :idStr LIMIT 1";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("uuid", resolveUuid(id))
                .addValue("idStr", id.trim());

        Map<UUID, DemoAiEvent> map = new HashMap<>();
        jdbcTemplate.query(sql, params, rs -> {
            UUID rootId = (UUID) rs.getObject("id");
            map.put(rootId, mapEventRoot(rs));
        });

        if (map.isEmpty()) {
            return Optional.empty();
        }

        loadTimelineChildren(map);
        return map.values().stream().findFirst().map(DemoAiEvent::copy);
    }

    @Override
    public DemoAiEvent save(DemoAiEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        DemoAiEvent toPersist = event.copy();

        if (toPersist.id == null || toPersist.id.isBlank()) {
            toPersist.id = "AI-E-" + OffsetDateTime.now(clock.withZone(ZONE)).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + "-" + String.format("%03d", (int) (Math.random() * 900 + 100));
        }

        UUID rootUuid = resolveUuid(toPersist.id);
        String eventNo = toPersist.id;

        // 1. 保存 / 更新主表 safety.ai_event
        saveRootEvent(rootUuid, eventNo, toPersist);

        // 2. 追加审计时间线 safety.ai_event_timeline
        appendTimelineNodes(rootUuid, toPersist);

        return findById(eventNo).orElse(toPersist);
    }

    @Override
    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM safety.ai_event", Collections.emptyMap(), Long.class);
        return c != null ? c : 0L;
    }

    @Override
    public void clearDemoData() {
        jdbcTemplate.update("DELETE FROM safety.ai_event_timeline", Collections.emptyMap());
        jdbcTemplate.update("DELETE FROM safety.ai_event", Collections.emptyMap());
    }

    // ---------- 内部持久化辅助 ----------

    private void saveRootEvent(UUID rootUuid, String eventNo, DemoAiEvent e) {
        String checkSql = "SELECT id FROM safety.ai_event WHERE id = :id OR event_no = :eventNo LIMIT 1";
        List<UUID> existingIds = jdbcTemplate.query(
                checkSql,
                new MapSqlParameterSource().addValue("id", rootUuid).addValue("eventNo", eventNo),
                (rs, rowNum) -> (UUID) rs.getObject(1)
        );

        Timestamp occurredAtTs = e.occurredAt != null ? Timestamp.from(e.occurredAt.toInstant())
                : Timestamp.from(OffsetDateTime.now(clock).toInstant());
        Timestamp reviewTimeTs = null;
        if (e.reviewTime != null && !e.reviewTime.isBlank()) {
            try {
                java.time.LocalTime lt = java.time.LocalTime.parse(e.reviewTime, HMS);
                reviewTimeTs = Timestamp.from(java.time.LocalDate.now(clock).atTime(lt).atZone(ZONE).toInstant());
            } catch (Exception ignored) {
            }
        }

        // 置信度与阈值转为 openGauss NUMERIC(5,4) 范围：0.0000 ~ 1.0000
        BigDecimal confBd = null;
        if (e.confidence > 0) {
            double c = e.confidence > 1.0 ? e.confidence / 100.0 : e.confidence;
            confBd = BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, c))).setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal threshBd = null;
        if (e.threshold > 0) {
            double t = e.threshold > 1.0 ? e.threshold / 100.0 : e.threshold;
            threshBd = BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, t))).setScale(4, RoundingMode.HALF_UP);
        }

        // JSONB detection_boxes (若有 snapshotUrl，打包存入 JSONB，0 DDL 架构升级)
        PGobject boxesPgo = new PGobject();
        boxesPgo.setType("jsonb");
        try {
            if (e.snapshotUrl != null && !e.snapshotUrl.isBlank()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("boxes", e.boxes == null ? Collections.emptyList() : e.boxes);
                payload.put("snapshotUrl", e.snapshotUrl);
                boxesPgo.setValue(objectMapper.writeValueAsString(payload));
            } else {
                boxesPgo.setValue(objectMapper.writeValueAsString(e.boxes == null ? Collections.emptyList() : e.boxes));
            }
        } catch (Exception ex) {
            try {
                boxesPgo.setValue("[]");
            } catch (SQLException ignored) {
            }
        }

        // 复核人 / 派单人用户 code
        String reviewerCode = null;
        if (e.reviewer != null && !e.reviewer.isBlank()) {
            DemoUser u = masterData.resolveUser(null, e.reviewer);
            reviewerCode = u != null ? u.id() : null;
        }
        String assigneeCode = e.assigneeUserCode;
        if (assigneeCode == null && e.assignee != null && !e.assignee.isBlank()) {
            DemoUser u = masterData.resolveUser(null, e.assignee);
            assigneeCode = u != null ? u.id() : null;
        }

        // 关联告警 UUID 外键
        UUID alertUuid = null;
        if (e.linkedAlertId != null && !e.linkedAlertId.isBlank()) {
            alertUuid = JdbcAlertRepository.resolveUuid(e.linkedAlertId);
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", rootUuid)
                .addValue("eventNo", eventNo)
                .addValue("eventType", e.type != null ? e.type : "未佩戴安全帽")
                .addValue("cameraCode", e.camera)
                .addValue("cameraNameSnapshot", e.cameraName)
                .addValue("areaCode", e.area)
                .addValue("confidence", confBd)
                .addValue("durationSec", BigDecimal.valueOf(e.durationSec).setScale(2, RoundingMode.HALF_UP))
                .addValue("modelCode", e.model)
                .addValue("threshold", threshBd)
                .addValue("statusCode", e.statusCode != null ? e.statusCode : AiReviewStatuses.PENDING)
                .addValue("riskCode", e.riskCode != null ? e.riskCode : AiRiskLevels.MEDIUM)
                .addValue("cameraHealthCode", e.health != null ? e.health : "正常")
                .addValue("sceneType", e.scene)
                .addValue("detectionBoxes", boxesPgo)
                .addValue("ruleCode", e.rule)
                .addValue("relatedPersonCode", e.relatedPerson)
                .addValue("relatedDeviceCode", e.relatedDevice)
                .addValue("judgeText", e.judgeText)
                .addValue("reviewerUserCode", reviewerCode)
                .addValue("reviewerNameSnapshot", e.reviewer)
                .addValue("reviewTime", reviewTimeTs)
                .addValue("falseReason", e.falseReason)
                .addValue("assigneeUserCode", assigneeCode)
                .addValue("assigneeNameSnapshot", e.assignee)
                .addValue("assignmentPriority", e.assignmentPriority)
                .addValue("processStatusCode", e.processStatus)
                .addValue("assignmentNote", e.assignmentNote)
                .addValue("linkedAlertId", alertUuid)
                .addValue("occurredAt", occurredAtTs);

        if (existingIds.isEmpty()) {
            String insertSql = """
                    INSERT INTO safety.ai_event (
                        id, event_no, event_type, camera_code, camera_name_snapshot, area_code,
                        confidence, duration_sec, model_code, threshold, status_code, risk_code,
                        camera_health_code, scene_type, detection_boxes, rule_code, related_person_code,
                        related_device_code, judge_text, reviewer_user_code, reviewer_name_snapshot,
                        review_time, false_reason, assignee_user_code, assignee_name_snapshot,
                        assignment_priority, process_status_code, assignment_note, linked_alert_id,
                        occurred_at, updated_at, created_at
                    ) VALUES (
                        :id, :eventNo, :eventType, :cameraCode, :cameraNameSnapshot, :areaCode,
                        :confidence, :durationSec, :modelCode, :threshold, :statusCode, :riskCode,
                        :cameraHealthCode, :sceneType, :detectionBoxes, :ruleCode, :relatedPersonCode,
                        :relatedDeviceCode, :judgeText, :reviewerUserCode, :reviewerNameSnapshot,
                        :reviewTime, :falseReason, :assigneeUserCode, :assigneeNameSnapshot,
                        :assignmentPriority, :processStatusCode, :assignmentNote, :linkedAlertId,
                        :occurredAt, now(), now()
                    )
                    """;
            jdbcTemplate.update(insertSql, params);
        } else {
            UUID existingId = existingIds.get(0);
            params.addValue("existingId", existingId);
            String updateSql = """
                    UPDATE safety.ai_event SET
                        event_type = :eventType,
                        camera_code = :cameraCode,
                        camera_name_snapshot = :cameraNameSnapshot,
                        area_code = :areaCode,
                        confidence = :confidence,
                        duration_sec = :durationSec,
                        model_code = :modelCode,
                        threshold = :threshold,
                        status_code = :statusCode,
                        risk_code = :riskCode,
                        camera_health_code = :cameraHealthCode,
                        scene_type = :sceneType,
                        detection_boxes = :detectionBoxes,
                        rule_code = :ruleCode,
                        related_person_code = :relatedPersonCode,
                        related_device_code = :relatedDeviceCode,
                        judge_text = :judgeText,
                        reviewer_user_code = :reviewerUserCode,
                        reviewer_name_snapshot = :reviewerNameSnapshot,
                        review_time = :reviewTime,
                        false_reason = :falseReason,
                        assignee_user_code = :assigneeUserCode,
                        assignee_name_snapshot = :assigneeNameSnapshot,
                        assignment_priority = :assignmentPriority,
                        process_status_code = :processStatusCode,
                        assignment_note = :assignmentNote,
                        linked_alert_id = :linkedAlertId,
                        occurred_at = :occurredAt,
                        updated_at = now()
                    WHERE id = :existingId
                    """;
            jdbcTemplate.update(updateSql, params);
        }
    }

    private void appendTimelineNodes(UUID rootUuid, DemoAiEvent e) {
        if (e.timeline == null || e.timeline.isEmpty()) {
            return;
        }

        String existingSql = "SELECT sequence_no FROM safety.ai_event_timeline WHERE ai_event_id = :rootId";
        List<Integer> existingSeqs = jdbcTemplate.query(
                existingSql,
                new MapSqlParameterSource("rootId", rootUuid),
                (rs, rowNum) -> rs.getInt(1)
        );
        Set<Integer> existingSet = new HashSet<>(existingSeqs);

        String insertSql = """
                INSERT INTO safety.ai_event_timeline (
                    ai_event_id, sequence_no, event_type, state_code,
                    operator_user_code, operator_name_snapshot, message, occurred_at, created_at
                ) VALUES (
                    :aiEventId, :sequenceNo, :eventType, :stateCode,
                    :operatorUserCode, :operatorNameSnapshot, :message, :occurredAt, now()
                )
                """;

        int maxSeq = existingSeqs.stream().mapToInt(Integer::intValue).max().orElse(0);

        List<MapSqlParameterSource> batchParams = new ArrayList<>();
        for (int i = 0; i < e.timeline.size(); i++) {
            AiTimelineNode node = e.timeline.get(i);
            int seq = node.sequenceNo() != null ? node.sequenceNo() : (i + 1);
            if (seq <= maxSeq && existingSet.contains(seq)) {
                continue;
            }

            String eventType = node.eventType() != null ? node.eventType() : deriveEventType(node.text());
            String stateCode = node.state() != null ? node.state() : "done";

            Timestamp nodeOccurredAt = Timestamp.from(OffsetDateTime.now(clock).toInstant());
            if (node.time() != null && !node.time().isBlank()) {
                try {
                    java.time.LocalTime lt = java.time.LocalTime.parse(node.time(), HMS);
                    nodeOccurredAt = Timestamp.from(java.time.LocalDate.now(clock).atTime(lt).atZone(ZONE).toInstant());
                } catch (Exception ignored) {
                }
            }

            batchParams.add(new MapSqlParameterSource()
                    .addValue("aiEventId", rootUuid)
                    .addValue("sequenceNo", seq)
                    .addValue("eventType", eventType)
                    .addValue("stateCode", stateCode)
                    .addValue("operatorUserCode", null)
                    .addValue("operatorNameSnapshot", null)
                    .addValue("message", node.text() != null ? node.text() : "")
                    .addValue("occurredAt", nodeOccurredAt));
        }

        if (!batchParams.isEmpty()) {
            jdbcTemplate.batchUpdate(insertSql, batchParams.toArray(new MapSqlParameterSource[0]));
        }
    }

    private String deriveEventType(String text) {
        if (text == null) return AiTimelineEventTypes.NOTE;
        if (text.contains("检测到")) return AiTimelineEventTypes.DETECTED;
        if (text.contains("复核队列") || text.contains("待复核")) return AiTimelineEventTypes.QUEUED;
        if (text.contains("确认违规")) return AiTimelineEventTypes.CONFIRMED;
        if (text.contains("误报")) return AiTimelineEventTypes.FALSE_POSITIVE;
        if (text.contains("不确定")) return AiTimelineEventTypes.UNCERTAIN;
        if (text.contains("派单")) return AiTimelineEventTypes.ASSIGNED;
        if (text.contains("处置")) return AiTimelineEventTypes.PROCESSING;
        if (text.contains("关闭")) return AiTimelineEventTypes.CLOSED;
        if (text.contains("安全告警")) return AiTimelineEventTypes.ALERT_LINKED;
        return AiTimelineEventTypes.NOTE;
    }

    private void loadTimelineChildren(Map<UUID, DemoAiEvent> map) {
        if (map.isEmpty()) {
            return;
        }

        String sql = """
                SELECT ai_event_id, sequence_no, event_type, state_code,
                       operator_user_code, operator_name_snapshot, message, occurred_at
                FROM safety.ai_event_timeline
                WHERE ai_event_id IN (:eventIds)
                ORDER BY ai_event_id, sequence_no ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource("eventIds", map.keySet());
        jdbcTemplate.query(sql, params, rs -> {
            UUID eventId = (UUID) rs.getObject("ai_event_id");
            DemoAiEvent event = map.get(eventId);
            if (event != null) {
                int seq = rs.getInt("sequence_no");
                String eventType = rs.getString("event_type");
                String stateCode = rs.getString("state_code");
                String msg = rs.getString("message");
                Timestamp ts = rs.getTimestamp("occurred_at");
                String timeStr = ts != null ? ts.toInstant().atZone(ZONE).format(HMS) : "";

                event.timeline.add(new AiTimelineNode(timeStr, msg, stateCode, eventType, seq));
            }
        });
    }

    private DemoAiEvent mapEventRoot(ResultSet rs) throws SQLException {
        DemoAiEvent e = new DemoAiEvent();
        String eventNo = rs.getString("event_no");
        e.id = (eventNo != null && !eventNo.isBlank()) ? eventNo : rs.getString("id");
        e.type = rs.getString("event_type");
        e.camera = rs.getString("camera_code");
        e.cameraName = rs.getString("camera_name_snapshot");
        e.area = rs.getString("area_code");

        BigDecimal confBd = rs.getBigDecimal("confidence");
        if (confBd != null) {
            double c = confBd.doubleValue();
            e.confidence = c <= 1.0 ? Math.round(c * 1000.0) / 10.0 : c;
        } else {
            e.confidence = 0.0;
        }

        BigDecimal durBd = rs.getBigDecimal("duration_sec");
        e.durationSec = durBd != null ? durBd.doubleValue() : 0.0;

        e.model = rs.getString("model_code");

        BigDecimal thBd = rs.getBigDecimal("threshold");
        if (thBd != null) {
            double t = thBd.doubleValue();
            e.threshold = t <= 1.0 ? Math.round(t * 1000.0) / 10.0 : t;
        } else {
            e.threshold = 0.0;
        }

        e.statusCode = rs.getString("status_code");
        e.riskCode = rs.getString("risk_code");
        e.health = rs.getString("camera_health_code");
        e.scene = rs.getString("scene_type");

        String boxesJson = rs.getString("detection_boxes");
        if (boxesJson != null && !boxesJson.isBlank()) {
            try {
                if (boxesJson.trim().startsWith("{")) {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(boxesJson);
                    if (node.has("boxes")) {
                        e.boxes = objectMapper.convertValue(node.get("boxes"), new TypeReference<List<AiBox>>() {});
                    } else {
                        e.boxes = new ArrayList<>();
                    }
                    if (node.has("snapshotUrl") && !node.get("snapshotUrl").isNull()) {
                        e.snapshotUrl = node.get("snapshotUrl").asText(null);
                    }
                } else {
                    e.boxes = objectMapper.readValue(boxesJson, new TypeReference<List<AiBox>>() {});
                }
            } catch (Exception ex) {
                e.boxes = new ArrayList<>();
            }
        } else {
            e.boxes = new ArrayList<>();
        }

        e.rule = rs.getString("rule_code");
        e.relatedPerson = rs.getString("related_person_code");
        e.relatedDevice = rs.getString("related_device_code");
        e.judgeText = rs.getString("judge_text");

        e.reviewer = rs.getString("reviewer_name_snapshot");
        Timestamp rt = rs.getTimestamp("review_time");
        if (rt != null) {
            e.reviewTime = rt.toInstant().atZone(ZONE).format(HMS);
        }

        e.falseReason = rs.getString("false_reason");
        e.assigneeUserCode = rs.getString("assignee_user_code");
        e.assignee = rs.getString("assignee_name_snapshot");
        e.assignmentPriority = rs.getString("assignment_priority");
        e.processStatus = rs.getString("process_status_code");
        e.assignmentNote = rs.getString("assignment_note");

        // 优先使用联查出的权威 alert_no，若无则转字符串
        String linkedNo = rs.getString("linked_alert_no");
        if (linkedNo != null && !linkedNo.isBlank()) {
            e.linkedAlertId = linkedNo;
        } else {
            Object alertObj = rs.getObject("linked_alert_id");
            e.linkedAlertId = alertObj != null ? alertObj.toString() : null;
        }

        Timestamp occTs = rs.getTimestamp("occurred_at");
        if (occTs != null) {
            e.occurredAt = occTs.toInstant().atOffset(ZoneOffset.ofHours(8));
            e.time = occTs.toInstant().atZone(ZONE).format(HMS);
        }

        Timestamp updTs = rs.getTimestamp("updated_at");
        if (updTs != null) {
            e.updatedAt = updTs.toInstant().atOffset(ZoneOffset.ofHours(8));
        }

        e.timeline = new ArrayList<>();
        return e;
    }

    private void buildQueryConditions(AiEventQuery query, StringBuilder sql, MapSqlParameterSource params) {
        if (query.keyword() != null && !query.keyword().isBlank()) {
            String kw = "%" + query.keyword().trim() + "%";
            sql.append("""
                    AND (e.id::text LIKE :kw
                      OR e.event_no LIKE :kw
                      OR e.event_type LIKE :kw
                      OR e.camera_code LIKE :kw
                      OR e.camera_name_snapshot LIKE :kw
                      OR e.area_code LIKE :kw
                      OR e.model_code LIKE :kw
                      OR e.related_person_code LIKE :kw)
                    """);
            params.addValue("kw", kw);
        }
        if (query.type() != null && !query.type().isBlank()) {
            sql.append(" AND e.event_type = :type ");
            params.addValue("type", query.type());
        }
        if (query.area() != null && !query.area().isBlank()) {
            sql.append(" AND e.area_code = :area ");
            params.addValue("area", query.area());
        }
        if (query.camera() != null && !query.camera().isBlank()) {
            sql.append(" AND e.camera_code = :camera ");
            params.addValue("camera", query.camera());
        }
        if (query.status() != null && !query.status().isBlank()) {
            sql.append(" AND e.status_code = :status ");
            params.addValue("status", AiReviewStatuses.normalize(query.status()));
        }
        if (query.risk() != null && !query.risk().isBlank()) {
            sql.append(" AND e.risk_code = :risk ");
            params.addValue("risk", AiRiskLevels.normalize(query.risk()));
        }
        if (query.confidence() != null && !query.confidence().isBlank()) {
            switch (query.confidence()) {
                case "high" -> sql.append(" AND e.confidence >= 0.8500 ");
                case "mid" -> sql.append(" AND e.confidence >= 0.7000 AND e.confidence < 0.8500 ");
                case "low" -> sql.append(" AND e.confidence < 0.7000 ");
                default -> {}
            }
        }
        if (query.timeBucket() != null && !query.timeBucket().isBlank()) {
            switch (query.timeBucket()) {
                case "1h" -> sql.append(" AND timezone('Asia/Shanghai', e.occurred_at)::time >= '22:00:00'::time ");
                case "2h" -> sql.append(" AND timezone('Asia/Shanghai', e.occurred_at)::time >= '21:00:00'::time AND timezone('Asia/Shanghai', e.occurred_at)::time < '22:00:00'::time ");
                case "earlier" -> sql.append(" AND timezone('Asia/Shanghai', e.occurred_at)::time < '21:00:00'::time ");
                default -> {}
            }
        }
    }
}
