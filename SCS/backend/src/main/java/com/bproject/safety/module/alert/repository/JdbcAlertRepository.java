package com.bproject.safety.module.alert.repository;

import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertPageResult;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.AlertTimelineEventTypes;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.LinkageStep;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.model.TimelineEvent;
import com.bproject.safety.module.alert.model.TreatmentRecord;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 openGauss (PostgreSQL 兼容模式) 的告警主聚合持久化仓储实现。
 * 覆盖告警主表 {@code safety.safety_alert} 及 5 个级联子表：
 * <ul>
 *   <li>{@code safety.safety_alert_timeline}（审计流、严格 append-only、sequence_no 保序）</li>
 *   <li>{@code safety.safety_alert_evidence}（单表 + JSONB Payload 多态结构化证据）</li>
 *   <li>{@code safety.safety_alert_treatment}（现场处置执行记录）</li>
 *   <li>{@code safety.safety_alert_linkage}（现场联动会话）</li>
 *   <li>{@code safety.safety_alert_linkage_step}（联动步骤明细）</li>
 * </ul>
 *
 * <p>严格遵守 DDL 权威约束（0 DDL 变更），支持 copy-on-read / copy-on-write 语义。</p>
 */
@Repository
@Profile("server")
public class JdbcAlertRepository implements AlertRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcAlertRepository.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String BASE_SELECT_ROOT = """
            SELECT id, alert_no, title, source, event_type, risk_level_code, status_code,
                   previous_risk_level_code, upgraded_from_code, mobile_stage_code, priority,
                   area_code, area_name_snapshot, target_object_type, target_object_id, target_object_name_snapshot,
                   assignee_user_code, assignee_name_snapshot, confirm_user_code, confirm_user_name_snapshot,
                   review_user_code, review_user_name_snapshot, review_note,
                   decision_source_type, decision_source_code, decision_source_version,
                   rule_code, rule_version, linked_ai_event_id, origin,
                   edge_node_code, offline_event_id, rule_version_used, offline_occurred,
                   sync_delay_sec, synced_at, dedup_key, duration_sec, sla_limit_min,
                   sla_deadline, occurred_at, detected_at, server_received_at, confirmed_at,
                   accepted_at, arrived_at, closed_at, linkage_available, linkage_finished,
                   linkage_failed, takeover, lock_version, created_at, updated_at
            FROM safety.safety_alert
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DemoMasterData masterData;
    private final Clock clock;

    public JdbcAlertRepository(NamedParameterJdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               DemoMasterData masterData,
                               Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.masterData = masterData;
        this.clock = clock;
    }

    /**
     * 将业务 ID（如 ALM-20260904-001）转为确定性 UUID；若已是有效 UUID 字符串则直接解析。
     */
    public static UUID resolveUuid(String alertId) {
        if (alertId == null || alertId.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(alertId.trim());
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(alertId.trim().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public List<DemoAlert> findAll() {
        String sql = BASE_SELECT_ROOT + " ORDER BY occurred_at DESC, id DESC";
        Map<UUID, DemoAlert> alertMap = new LinkedHashMap<>();
        jdbcTemplate.query(sql, Collections.emptyMap(), rs -> {
            UUID id = (UUID) rs.getObject("id");
            DemoAlert alert = mapAlertRoot(rs);
            alertMap.put(id, alert);
        });

        loadAggregateChildren(alertMap);
        return alertMap.values().stream().map(DemoAlert::copy).toList();
    }

    @Override
    public List<DemoAlert> filter(AlertQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder(BASE_SELECT_ROOT).append(" WHERE 1=1 ");
        buildQueryConditions(query, sql, params);
        sql.append(" ORDER BY occurred_at DESC, id DESC");

        Map<UUID, DemoAlert> alertMap = new LinkedHashMap<>();
        jdbcTemplate.query(sql.toString(), params, rs -> {
            UUID id = (UUID) rs.getObject("id");
            DemoAlert alert = mapAlertRoot(rs);
            alertMap.put(id, alert);
        });

        loadAggregateChildren(alertMap);
        return alertMap.values().stream().map(DemoAlert::copy).toList();
    }

    @Override
    public AlertPageResult page(AlertQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM safety.safety_alert WHERE 1=1 ");
        buildQueryConditions(query, countSql, params);

        Long totalObj = jdbcTemplate.queryForObject(countSql.toString(), params, Long.class);
        int total = totalObj != null ? totalObj.intValue() : 0;

        if (total == 0) {
            return new AlertPageResult(query.page(), query.pageSize(), 0, Collections.emptyList());
        }

        StringBuilder pageSql = new StringBuilder(BASE_SELECT_ROOT).append(" WHERE 1=1 ");
        buildQueryConditions(query, pageSql, params);
        pageSql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset");

        int offset = (query.page() - 1) * query.pageSize();
        params.addValue("limit", query.pageSize());
        params.addValue("offset", offset);

        Map<UUID, DemoAlert> alertMap = new LinkedHashMap<>();
        jdbcTemplate.query(pageSql.toString(), params, rs -> {
            UUID id = (UUID) rs.getObject("id");
            DemoAlert alert = mapAlertRoot(rs);
            alertMap.put(id, alert);
        });

        loadAggregateChildren(alertMap);
        List<DemoAlert> list = alertMap.values().stream().map(DemoAlert::copy).toList();
        return new AlertPageResult(query.page(), query.pageSize(), total, list);
    }

    @Override
    public Optional<DemoAlert> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        UUID uuidCandidate = resolveUuid(id);
        String sql = BASE_SELECT_ROOT + " WHERE alert_no = :alertNo OR id = :uuid LIMIT 1";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("alertNo", id.trim())
                .addValue("uuid", uuidCandidate);

        Map<UUID, DemoAlert> alertMap = new HashMap<>();
        jdbcTemplate.query(sql, params, rs -> {
            UUID rootId = (UUID) rs.getObject("id");
            alertMap.put(rootId, mapAlertRoot(rs));
        });

        if (alertMap.isEmpty()) {
            return Optional.empty();
        }

        loadAggregateChildren(alertMap);
        return alertMap.values().stream().findFirst().map(DemoAlert::copy);
    }

    @Override
    public Optional<DemoAlert> findOpenByDedupKey(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return Optional.empty();
        }
        String sql = BASE_SELECT_ROOT + " WHERE dedup_key = :dedupKey AND status_code <> 'CLOSED' "
                + "ORDER BY occurred_at DESC, id DESC LIMIT 1";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("dedupKey", dedupKey.trim());

        Map<UUID, DemoAlert> alertMap = new HashMap<>();
        jdbcTemplate.query(sql, params, rs -> {
            UUID rootId = (UUID) rs.getObject("id");
            alertMap.put(rootId, mapAlertRoot(rs));
        });

        if (alertMap.isEmpty()) {
            return Optional.empty();
        }

        loadAggregateChildren(alertMap);
        return alertMap.values().stream().findFirst().map(DemoAlert::copy);
    }

    @Override
    public DemoAlert save(DemoAlert alert) {
        Objects.requireNonNull(alert, "alert must not be null");
        DemoAlert toPersist = alert.copy();

        if (toPersist.id == null || toPersist.id.isBlank()) {
            toPersist.id = "ALM-" + LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + "-" + String.format("%03d", (int) (Math.random() * 900 + 100));
        }

        UUID rootUuid = resolveUuid(toPersist.id);
        String alertNo = toPersist.id;

        // 1. 保存 / 更新聚合根 safety_alert
        saveRootAlert(rootUuid, alertNo, toPersist);

        // 2. 追加审计时间线 safety_alert_timeline（严格 append-only）
        appendTimelineEvents(rootUuid, toPersist);

        // 3. 保存结构化证据 safety_alert_evidence
        saveEvidence(rootUuid, toPersist);

        // 4. 保存现场处置记录 safety_alert_treatment
        saveTreatment(rootUuid, toPersist);

        // 5. 保存现场联动 safety_alert_linkage 及步骤 safety_alert_linkage_step
        saveLinkage(rootUuid, toPersist);

        return toPersist.copy();
    }

    @Override
    public long count() {
        Long val = jdbcTemplate.queryForObject("SELECT count(*) FROM safety.safety_alert", Collections.emptyMap(), Long.class);
        return val != null ? val : 0L;
    }

    // ---------- 内部持久化辅助 ----------

    private void saveRootAlert(UUID rootUuid, String alertNo, DemoAlert a) {
        List<UUID> existingIds = jdbcTemplate.query(
                "SELECT id FROM safety.safety_alert WHERE alert_no = :alertNo OR id = :rootUuid LIMIT 1",
                Map.of("alertNo", alertNo, "rootUuid", rootUuid),
                (rs, rowNum) -> (UUID) rs.getObject(1)
        );

        String areaCode = resolveAreaCode(a.area);
        String assigneeCode = a.assigneeUserCode != null ? a.assigneeUserCode : resolveUserCode(a.assignee);
        String confirmCode = resolveUserCode(a.confirmUser);
        String reviewCode = resolveUserCode(a.reviewUser);

        Timestamp occurredAtTs = a.occurredAt != null ? Timestamp.from(a.occurredAt.toInstant())
                : Timestamp.from(OffsetDateTime.now(clock).toInstant());
        Timestamp slaDeadlineTs = a.slaDeadline != null ? Timestamp.from(a.slaDeadline.toInstant()) : null;
        Timestamp acceptedAtTs = a.acceptedAt != null ? Timestamp.from(a.acceptedAt.toInstant()) : null;
        Timestamp arrivedAtTs = a.arrivedAt != null ? Timestamp.from(a.arrivedAt.toInstant()) : null;
        Timestamp closedAtTs = AlertStatuses.CLOSED.equals(a.statusCode)
                ? Timestamp.from(a.updatedAt != null ? a.updatedAt.toInstant() : OffsetDateTime.now(clock).toInstant()) : null;
        Timestamp confirmedAtTs = null;
        if (a.confirmTime != null && !a.confirmTime.isBlank()) {
            try {
                LocalTime lt = LocalTime.parse(a.confirmTime, HMS);
                confirmedAtTs = Timestamp.from(LocalDate.now(clock).atTime(lt).atZone(ZONE).toInstant());
            } catch (Exception ignored) {
            }
        }

        String origin = a.origin != null ? a.origin : "CLOUD";
        String edgeNodeCode = a.edgeReplay != null ? a.edgeReplay.edgeNodeId : null;
        String offlineEventId = a.edgeReplay != null ? a.edgeReplay.offlineEventId : null;
        String ruleVersionUsed = a.edgeReplay != null ? a.edgeReplay.ruleVersionUsed : null;
        boolean offlineOccurred = a.edgeReplay != null && a.edgeReplay.offlineOccurred;
        Long syncDelaySec = a.edgeReplay != null ? a.edgeReplay.syncDelaySec : null;
        Timestamp syncedAtTs = a.edgeReplay != null && a.edgeReplay.syncedAt != null
                ? Timestamp.from(a.edgeReplay.syncedAt.toInstant()) : null;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", rootUuid)
                .addValue("alertNo", alertNo)
                .addValue("title", a.title != null ? a.title : "安全告警")
                .addValue("source", a.source)
                .addValue("eventType", a.eventType)
                .addValue("riskLevelCode", a.riskCode != null ? a.riskCode : RiskLevels.NORMAL)
                .addValue("statusCode", a.statusCode != null ? a.statusCode : AlertStatuses.PENDING_CONFIRM)
                .addValue("previousRiskLevelCode", a.previousRiskLevelCode)
                .addValue("upgradedFromCode", a.upgradedFromCode)
                .addValue("mobileStageCode", a.mobileStage)
                .addValue("priority", a.priority)
                .addValue("areaCode", areaCode)
                .addValue("areaNameSnapshot", a.area)
                .addValue("targetObjectType", null)
                .addValue("targetObjectId", a.target)
                .addValue("targetObjectNameSnapshot", a.target)
                .addValue("assigneeUserCode", assigneeCode)
                .addValue("assigneeNameSnapshot", a.assignee)
                .addValue("confirmUserCode", confirmCode)
                .addValue("confirmUserNameSnapshot", a.confirmUser)
                .addValue("reviewUserCode", reviewCode)
                .addValue("reviewUserNameSnapshot", a.reviewUser)
                .addValue("reviewNote", a.reviewNote)
                .addValue("decisionSourceType", a.decisionSourceType)
                .addValue("decisionSourceCode", a.decisionSourceCode)
                .addValue("decisionSourceVersion", a.decisionSourceVersion)
                .addValue("ruleCode", a.ruleId)
                .addValue("ruleVersion", a.ruleVersion)
                .addValue("origin", origin)
                .addValue("edgeNodeCode", edgeNodeCode)
                .addValue("offlineEventId", offlineEventId)
                .addValue("ruleVersionUsed", ruleVersionUsed)
                .addValue("offlineOccurred", offlineOccurred)
                .addValue("syncDelaySec", syncDelaySec)
                .addValue("syncedAt", syncedAtTs)
                .addValue("dedupKey", a.dedupKey)
                .addValue("durationSec", a.durationSec)
                .addValue("slaLimitMin", a.slaLimitMin)
                .addValue("slaDeadline", slaDeadlineTs)
                .addValue("occurredAt", occurredAtTs)
                .addValue("confirmedAt", confirmedAtTs)
                .addValue("acceptedAt", acceptedAtTs)
                .addValue("arrivedAt", arrivedAtTs)
                .addValue("closedAt", closedAtTs)
                .addValue("linkageAvailable", a.linkageAvailable)
                .addValue("linkageFinished", a.linkageFinished)
                .addValue("linkageFailed", a.linkageFailed)
                .addValue("takeover", a.takeover);

        if (existingIds.isEmpty()) {
            String insertSql = """
                    INSERT INTO safety.safety_alert (
                        id, alert_no, title, source, event_type, risk_level_code, status_code,
                        previous_risk_level_code, upgraded_from_code, mobile_stage_code, priority,
                        area_code, area_name_snapshot, target_object_type, target_object_id, target_object_name_snapshot,
                        assignee_user_code, assignee_name_snapshot, confirm_user_code, confirm_user_name_snapshot,
                        review_user_code, review_user_name_snapshot, review_note,
                        decision_source_type, decision_source_code, decision_source_version,
                        rule_code, rule_version, origin, edge_node_code, offline_event_id, rule_version_used,
                        offline_occurred, sync_delay_sec, synced_at, dedup_key, duration_sec,
                        sla_limit_min, sla_deadline, occurred_at, confirmed_at, accepted_at, arrived_at, closed_at,
                        linkage_available, linkage_finished, linkage_failed, takeover, lock_version, created_at, updated_at
                    ) VALUES (
                        :id, :alertNo, :title, :source, :eventType, :riskLevelCode, :statusCode,
                        :previousRiskLevelCode, :upgradedFromCode, :mobileStageCode, :priority,
                        :areaCode, :areaNameSnapshot, :targetObjectType, :targetObjectId, :targetObjectNameSnapshot,
                        :assigneeUserCode, :assigneeNameSnapshot, :confirmUserCode, :confirmUserNameSnapshot,
                        :reviewUserCode, :reviewUserNameSnapshot, :reviewNote,
                        :decisionSourceType, :decisionSourceCode, :decisionSourceVersion,
                        :ruleCode, :ruleVersion, :origin, :edgeNodeCode, :offlineEventId, :ruleVersionUsed,
                        :offlineOccurred, :syncDelaySec, :syncedAt, :dedupKey, :durationSec,
                        :slaLimitMin, :slaDeadline, :occurredAt, :confirmedAt, :acceptedAt, :arrivedAt, :closedAt,
                        :linkageAvailable, :linkageFinished, :linkageFailed, :takeover, 0, now(), now()
                    )
                    """;
            jdbcTemplate.update(insertSql, params);
        } else {
            UUID existingId = existingIds.get(0);
            params.addValue("existingId", existingId);
            String updateSql = """
                    UPDATE safety.safety_alert SET
                        title = :title,
                        source = :source,
                        event_type = :eventType,
                        risk_level_code = :riskLevelCode,
                        status_code = :statusCode,
                        previous_risk_level_code = :previousRiskLevelCode,
                        upgraded_from_code = :upgradedFromCode,
                        mobile_stage_code = :mobileStageCode,
                        priority = :priority,
                        area_code = :areaCode,
                        area_name_snapshot = :areaNameSnapshot,
                        target_object_type = :targetObjectType,
                        target_object_id = :targetObjectId,
                        target_object_name_snapshot = :targetObjectNameSnapshot,
                        assignee_user_code = :assigneeUserCode,
                        assignee_name_snapshot = :assigneeNameSnapshot,
                        confirm_user_code = :confirmUserCode,
                        confirm_user_name_snapshot = :confirmUserNameSnapshot,
                        review_user_code = :reviewUserCode,
                        review_user_name_snapshot = :reviewUserNameSnapshot,
                        review_note = :reviewNote,
                        decision_source_type = :decisionSourceType,
                        decision_source_code = :decisionSourceCode,
                        decision_source_version = :decisionSourceVersion,
                        rule_code = :ruleCode,
                        rule_version = :ruleVersion,
                        origin = :origin,
                        edge_node_code = :edgeNodeCode,
                        offline_event_id = :offlineEventId,
                        rule_version_used = :ruleVersionUsed,
                        offline_occurred = :offlineOccurred,
                        sync_delay_sec = :syncDelaySec,
                        synced_at = :syncedAt,
                        dedup_key = :dedupKey,
                        duration_sec = :durationSec,
                        sla_limit_min = :slaLimitMin,
                        sla_deadline = :slaDeadline,
                        occurred_at = :occurredAt,
                        confirmed_at = :confirmedAt,
                        accepted_at = :acceptedAt,
                        arrived_at = :arrivedAt,
                        closed_at = :closedAt,
                        linkage_available = :linkageAvailable,
                        linkage_finished = :linkageFinished,
                        linkage_failed = :linkageFailed,
                        takeover = :takeover,
                        lock_version = lock_version + 1,
                        updated_at = now()
                    WHERE id = :existingId
                    """;
            jdbcTemplate.update(updateSql, params);
        }
    }

    private void appendTimelineEvents(UUID rootUuid, DemoAlert a) {
        if (a.timeline == null || a.timeline.isEmpty()) {
            return;
        }

        List<Integer> existingSeqs = jdbcTemplate.query(
                "SELECT sequence_no FROM safety.safety_alert_timeline WHERE alert_id = :alertId",
                Map.of("alertId", rootUuid),
                (rs, rowNum) -> rs.getInt(1)
        );
        Set<Integer> seqSet = new HashSet<>(existingSeqs);
        int maxSeq = seqSet.stream().max(Integer::compareTo).orElse(0);

        String insertSql = """
                INSERT INTO safety.safety_alert_timeline (
                    alert_id, sequence_no, event_type, state_code, message, occurred_at, created_at
                ) VALUES (
                    :alertId, :sequenceNo, :eventType, :stateCode, :message, :occurredAt, now()
                )
                """;

        for (TimelineEvent event : a.timeline) {
            int seq = event.sequenceNo() != null ? event.sequenceNo() : ++maxSeq;
            if (seqSet.contains(seq)) {
                // 已经持久化过的时间线节点严格只读，绝对不进行任何 UPDATE 或 DELETE
                continue;
            }
            seqSet.add(seq);

            String eventType = event.eventType() != null ? event.eventType() : inferTimelineEventType(event.text());
            String state = event.state() != null ? event.state() : "done";
            Timestamp occurredAtTs = event.at() != null ? Timestamp.from(event.at().toInstant())
                    : Timestamp.from(OffsetDateTime.now(clock).toInstant());

            jdbcTemplate.update(insertSql, new MapSqlParameterSource()
                    .addValue("alertId", rootUuid)
                    .addValue("sequenceNo", seq)
                    .addValue("eventType", eventType)
                    .addValue("stateCode", state)
                    .addValue("message", event.text())
                    .addValue("occurredAt", occurredAtTs)
            );
        }
    }

    private void saveEvidence(UUID rootUuid, DemoAlert a) {
        if (a.evidence == null) {
            return;
        }

        String evidenceType = resolveEvidenceType(a.evidence);
        String sourceCode = resolveEvidenceSource(a.evidence);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(a.evidence);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize alert evidence to JSON", e);
        }
        PGobject payloadPg = toPgJsonb(payloadJson);
        Timestamp occurredAtTs = Timestamp.from(a.occurredAt != null ? a.occurredAt.toInstant()
                : OffsetDateTime.now(clock).toInstant());

        List<Long> evidenceIds = jdbcTemplate.query(
                "SELECT id FROM safety.safety_alert_evidence WHERE alert_id = :alertId LIMIT 1",
                Map.of("alertId", rootUuid),
                (rs, rowNum) -> rs.getLong(1)
        );

        if (evidenceIds.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO safety.safety_alert_evidence (
                        alert_id, evidence_type, source_code, occurred_at, payload, created_at
                    ) VALUES (
                        :alertId, :evidenceType, :sourceCode, :occurredAt, :payload, now()
                    )
                    """, new MapSqlParameterSource()
                    .addValue("alertId", rootUuid)
                    .addValue("evidenceType", evidenceType)
                    .addValue("sourceCode", sourceCode)
                    .addValue("occurredAt", occurredAtTs)
                    .addValue("payload", payloadPg)
            );
        } else {
            jdbcTemplate.update("""
                    UPDATE safety.safety_alert_evidence
                    SET evidence_type = :evidenceType,
                        source_code = :sourceCode,
                        occurred_at = :occurredAt,
                        payload = :payload
                    WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", evidenceIds.get(0))
                    .addValue("evidenceType", evidenceType)
                    .addValue("sourceCode", sourceCode)
                    .addValue("occurredAt", occurredAtTs)
                    .addValue("payload", payloadPg)
            );
        }
    }

    private void saveTreatment(UUID rootUuid, DemoAlert a) {
        if (a.treatment == null) {
            return;
        }
        TreatmentRecord tr = a.treatment;
        PGobject measuresPg = tr.measures() != null ? toPgJsonb(toJson(tr.measures())) : null;
        PGobject attachmentsPg = tr.attachment() != null ? toPgJsonb(toJson(List.of(tr.attachment()))) : null;
        Timestamp submittedAtTs = Timestamp.from(OffsetDateTime.now(clock).toInstant());
        if (tr.submitTime() != null && !tr.submitTime().isBlank()) {
            try {
                LocalTime lt = LocalTime.parse(tr.submitTime(), HMS);
                submittedAtTs = Timestamp.from(LocalDate.now(clock).atTime(lt).atZone(ZONE).toInstant());
            } catch (Exception ignored) {
            }
        }

        List<Long> treatmentIds = jdbcTemplate.query(
                "SELECT id FROM safety.safety_alert_treatment WHERE alert_id = :alertId LIMIT 1",
                Map.of("alertId", rootUuid),
                (rs, rowNum) -> rs.getLong(1)
        );

        if (treatmentIds.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO safety.safety_alert_treatment (
                        alert_id, measures, summary, operator_name_snapshot, attachments, submitted_at, created_at
                    ) VALUES (
                        :alertId, :measures, :summary, :operatorName, :attachments, :submittedAt, now()
                    )
                    """, new MapSqlParameterSource()
                    .addValue("alertId", rootUuid)
                    .addValue("measures", measuresPg)
                    .addValue("summary", tr.result())
                    .addValue("operatorName", tr.handler())
                    .addValue("attachments", attachmentsPg)
                    .addValue("submittedAt", submittedAtTs)
            );
        } else {
            jdbcTemplate.update("""
                    UPDATE safety.safety_alert_treatment
                    SET measures = :measures,
                        summary = :summary,
                        operator_name_snapshot = :operatorName,
                        attachments = :attachments,
                        submitted_at = :submittedAt
                    WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", treatmentIds.get(0))
                    .addValue("measures", measuresPg)
                    .addValue("summary", tr.result())
                    .addValue("operatorName", tr.handler())
                    .addValue("attachments", attachmentsPg)
                    .addValue("submittedAt", submittedAtTs)
            );
        }
    }

    private void saveLinkage(UUID rootUuid, DemoAlert a) {
        if ((a.linkage == null || a.linkage.isEmpty()) && !a.linkageAvailable) {
            return;
        }

        String statusCode = a.linkageFinished ? "FINISHED"
                : (a.linkageFailed ? "FAILED"
                : (a.takeover ? "TAKEOVER" : "STARTED"));
        Timestamp startedAtTs = Timestamp.from(a.occurredAt != null ? a.occurredAt.toInstant()
                : OffsetDateTime.now(clock).toInstant());
        Timestamp finishedAtTs = a.linkageFinished ? Timestamp.from(OffsetDateTime.now(clock).toInstant()) : null;

        List<Long> linkageIds = jdbcTemplate.query(
                "SELECT id FROM safety.safety_alert_linkage WHERE alert_id = :alertId LIMIT 1",
                Map.of("alertId", rootUuid),
                (rs, rowNum) -> rs.getLong(1)
        );

        Long linkageId;
        if (linkageIds.isEmpty()) {
            linkageId = jdbcTemplate.queryForObject("""
                    INSERT INTO safety.safety_alert_linkage (
                        alert_id, status_code, started_at, finished_at, created_at
                    ) VALUES (
                        :alertId, :statusCode, :startedAt, :finishedAt, now()
                    ) RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("alertId", rootUuid)
                    .addValue("statusCode", statusCode)
                    .addValue("startedAt", startedAtTs)
                    .addValue("finishedAt", finishedAtTs),
                    Long.class
            );
        } else {
            linkageId = linkageIds.get(0);
            jdbcTemplate.update("""
                    UPDATE safety.safety_alert_linkage
                    SET status_code = :statusCode,
                        finished_at = :finishedAt
                    WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", linkageId)
                    .addValue("statusCode", statusCode)
                    .addValue("finishedAt", finishedAtTs)
            );
        }

        if (linkageId != null && a.linkage != null && !a.linkage.isEmpty()) {
            for (int i = 0; i < a.linkage.size(); i++) {
                LinkageStep step = a.linkage.get(i);
                int stepNo = i + 1;
                String stepName = (step.id() != null ? step.id() + ":" : "") + (step.label() != null ? step.label() : "");
                String stepStatus = step.state() != null ? step.state().toUpperCase() : "WAIT";
                String actionText = step.detail();

                List<Long> stepIds = jdbcTemplate.query(
                        "SELECT id FROM safety.safety_alert_linkage_step WHERE linkage_id = :linkageId AND step_no = :stepNo",
                        Map.of("linkageId", linkageId, "stepNo", stepNo),
                        (rs, rowNum) -> rs.getLong(1)
                );

                if (stepIds.isEmpty()) {
                    jdbcTemplate.update("""
                            INSERT INTO safety.safety_alert_linkage_step (
                                linkage_id, step_no, step_name, status_code, action_text, created_at
                            ) VALUES (
                                :linkageId, :stepNo, :stepName, :statusCode, :actionText, now()
                            )
                            """, new MapSqlParameterSource()
                            .addValue("linkageId", linkageId)
                            .addValue("stepNo", stepNo)
                            .addValue("stepName", stepName)
                            .addValue("statusCode", stepStatus)
                            .addValue("actionText", actionText)
                    );
                } else {
                    jdbcTemplate.update("""
                            UPDATE safety.safety_alert_linkage_step
                            SET step_name = :stepName,
                                status_code = :statusCode,
                                action_text = :actionText
                            WHERE id = :id
                            """, new MapSqlParameterSource()
                            .addValue("id", stepIds.get(0))
                            .addValue("stepName", stepName)
                            .addValue("statusCode", stepStatus)
                            .addValue("actionText", actionText)
                    );
                }
            }
        }
    }

    // ---------- 批量加载子表聚合数据（规避 N+1） ----------

    private void loadAggregateChildren(Map<UUID, DemoAlert> alertMap) {
        if (alertMap.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>(alertMap.keySet());
        Map<String, Object> idParam = Map.of("ids", ids);

        // 1. 批量加载时间线
        jdbcTemplate.query("""
                SELECT alert_id, sequence_no, event_type, state_code, message, occurred_at
                FROM safety.safety_alert_timeline
                WHERE alert_id IN (:ids)
                ORDER BY alert_id, sequence_no ASC
                """, idParam, rs -> {
            UUID alertId = (UUID) rs.getObject("alert_id");
            DemoAlert a = alertMap.get(alertId);
            if (a != null) {
                Timestamp ts = rs.getTimestamp("occurred_at");
                OffsetDateTime at = ts != null ? ts.toInstant().atZone(ZONE).toOffsetDateTime() : null;
                String time = at != null ? at.format(HMS) : null;
                a.timeline.add(TimelineEvent.event(at, time, rs.getString("message"),
                        rs.getString("state_code"), rs.getString("event_type"), rs.getInt("sequence_no")));
            }
        });

        // 2. 批量加载证据
        jdbcTemplate.query("""
                SELECT alert_id, evidence_type, source_code, occurred_at, payload
                FROM safety.safety_alert_evidence
                WHERE alert_id IN (:ids)
                """, idParam, rs -> {
            UUID alertId = (UUID) rs.getObject("alert_id");
            DemoAlert a = alertMap.get(alertId);
            if (a != null) {
                String payload = rs.getString("payload");
                String evType = rs.getString("evidence_type");
                a.evidence = deserializeEvidence(payload, evType);
            }
        });

        // 3. 批量加载处置记录
        jdbcTemplate.query("""
                SELECT alert_id, measures, summary, operator_name_snapshot, attachments, submitted_at
                FROM safety.safety_alert_treatment
                WHERE alert_id IN (:ids)
                ORDER BY id ASC
                """, idParam, rs -> {
            UUID alertId = (UUID) rs.getObject("alert_id");
            DemoAlert a = alertMap.get(alertId);
            if (a != null) {
                List<String> measures = deserializeMeasures(rs.getString("measures"));
                String summary = rs.getString("summary");
                String handler = rs.getString("operator_name_snapshot");
                String attachment = deserializeAttachment(rs.getString("attachments"));
                Timestamp subTs = rs.getTimestamp("submitted_at");
                String submitTime = subTs != null ? subTs.toInstant().atZone(ZONE).format(HMS) : null;
                a.treatment = new TreatmentRecord(measures, summary, attachment, null, submitTime, handler);
            }
        });

        // 4. 批量加载联动会话与步骤
        Map<Long, UUID> linkageToAlert = new HashMap<>();
        jdbcTemplate.query("""
                SELECT id, alert_id, status_code
                FROM safety.safety_alert_linkage
                WHERE alert_id IN (:ids)
                """, idParam, rs -> {
            linkageToAlert.put(rs.getLong("id"), (UUID) rs.getObject("alert_id"));
        });

        if (!linkageToAlert.isEmpty()) {
            jdbcTemplate.query("""
                    SELECT linkage_id, step_no, step_name, status_code, action_text
                    FROM safety.safety_alert_linkage_step
                    WHERE linkage_id IN (:linkageIds)
                    ORDER BY linkage_id, step_no ASC
                    """, Map.of("linkageIds", new ArrayList<>(linkageToAlert.keySet())), rs -> {
                Long linkageId = rs.getLong("linkage_id");
                UUID alertId = linkageToAlert.get(linkageId);
                DemoAlert a = alertMap.get(alertId);
                if (a != null) {
                    String stepName = rs.getString("step_name");
                    String stepId = "step-" + rs.getInt("step_no");
                    String label = stepName;
                    if (stepName != null && stepName.contains(":")) {
                        String[] parts = stepName.split(":", 2);
                        stepId = parts[0];
                        label = parts[1];
                    }
                    String statusCode = rs.getString("status_code");
                    String state = statusCode != null ? statusCode.toLowerCase() : "wait";
                    a.linkage.add(new LinkageStep(stepId, label, state, rs.getString("action_text"), null));
                }
            });
        }
    }

    // ---------- 查询条件与映射辅助 ----------

    private void buildQueryConditions(AlertQuery q, StringBuilder sql, MapSqlParameterSource params) {
        if (q.keyword() != null && !q.keyword().isBlank()) {
            String pattern = "%" + q.keyword().trim() + "%";
            sql.append("""
                    AND (alert_no ILIKE :kwPattern
                         OR title ILIKE :kwPattern
                         OR target_object_name_snapshot ILIKE :kwPattern
                         OR target_object_id ILIKE :kwPattern
                         OR rule_code ILIKE :kwPattern
                         OR assignee_name_snapshot ILIKE :kwPattern)
                    """);
            params.addValue("kwPattern", pattern);
        }
        if (q.risk() != null && !q.risk().isBlank()) {
            sql.append(" AND risk_level_code = :risk ");
            params.addValue("risk", RiskLevels.normalize(q.risk()));
        }
        if (q.status() != null && !q.status().isBlank()) {
            sql.append(" AND status_code = :status ");
            params.addValue("status", AlertStatuses.normalize(q.status()));
        }
        if (q.area() != null && !q.area().isBlank()) {
            sql.append(" AND (area_code = :area OR area_name_snapshot = :area) ");
            params.addValue("area", q.area().trim());
        }
        if (q.eventType() != null && !q.eventType().isBlank()) {
            sql.append(" AND event_type = :eventType ");
            params.addValue("eventType", q.eventType().trim());
        }
        if (q.source() != null && !q.source().isBlank()) {
            sql.append(" AND source = :source ");
            params.addValue("source", q.source().trim());
        }
        if (q.assignee() != null && !q.assignee().isBlank()) {
            sql.append(" AND (assignee_user_code = :assignee OR assignee_name_snapshot ILIKE :assigneePattern) ");
            params.addValue("assignee", q.assignee().trim());
            params.addValue("assigneePattern", "%" + q.assignee().trim() + "%");
        }
        if (q.from() != null && !q.from().isBlank()) {
            try {
                OffsetDateTime fromOdt = OffsetDateTime.parse(q.from());
                sql.append(" AND occurred_at >= :fromTs ");
                params.addValue("fromTs", Timestamp.from(fromOdt.toInstant()));
            } catch (Exception ignored) {
            }
        }
        if (q.to() != null && !q.to().isBlank()) {
            try {
                OffsetDateTime toOdt = OffsetDateTime.parse(q.to());
                sql.append(" AND occurred_at <= :toTs ");
                params.addValue("toTs", Timestamp.from(toOdt.toInstant()));
            } catch (Exception ignored) {
            }
        }
    }

    private DemoAlert mapAlertRoot(ResultSet rs) throws SQLException {
        DemoAlert a = new DemoAlert();
        a.id = rs.getString("alert_no");
        a.title = rs.getString("title");
        a.source = rs.getString("source");
        a.eventType = rs.getString("event_type");
        a.riskCode = rs.getString("risk_level_code");
        a.statusCode = rs.getString("status_code");
        a.previousRiskLevelCode = rs.getString("previous_risk_level_code");
        a.upgradedFromCode = rs.getString("upgraded_from_code");
        a.mobileStage = rs.getString("mobile_stage_code");
        a.priority = rs.getString("priority");
        a.area = rs.getString("area_name_snapshot");
        a.target = rs.getString("target_object_name_snapshot");
        a.assigneeUserCode = rs.getString("assignee_user_code");
        a.assignee = rs.getString("assignee_name_snapshot");
        a.confirmUser = rs.getString("confirm_user_name_snapshot");
        a.reviewUser = rs.getString("review_user_name_snapshot");
        a.reviewNote = rs.getString("review_note");
        a.decisionSourceType = rs.getString("decision_source_type");
        a.decisionSourceCode = rs.getString("decision_source_code");
        a.decisionSourceVersion = rs.getString("decision_source_version");
        a.ruleId = rs.getString("rule_code");
        a.ruleVersion = rs.getString("rule_version");
        a.origin = rs.getString("origin");
        a.dedupKey = rs.getString("dedup_key");
        a.durationSec = rs.getInt("duration_sec");

        int slaLimit = rs.getInt("sla_limit_min");
        a.slaLimitMin = rs.wasNull() ? null : slaLimit;

        Timestamp occurredTs = rs.getTimestamp("occurred_at");
        if (occurredTs != null) {
            a.occurredAt = occurredTs.toInstant().atZone(ZONE).toOffsetDateTime();
            a.time = a.occurredAt.format(HMS);
        }

        Timestamp slaDeadlineTs = rs.getTimestamp("sla_deadline");
        if (slaDeadlineTs != null) {
            a.slaDeadline = slaDeadlineTs.toInstant().atZone(ZONE).toOffsetDateTime();
            if (!AlertStatuses.CLOSED.equals(a.statusCode)) {
                a.slaRemainingSec = Duration.between(OffsetDateTime.now(clock), a.slaDeadline).toSeconds();
            }
        }

        Timestamp confirmedTs = rs.getTimestamp("confirmed_at");
        if (confirmedTs != null) {
            a.confirmTime = confirmedTs.toInstant().atZone(ZONE).format(HMS);
        }

        Timestamp acceptedTs = rs.getTimestamp("accepted_at");
        if (acceptedTs != null) {
            a.acceptedAt = acceptedTs.toInstant().atZone(ZONE).toOffsetDateTime();
            a.acceptTime = a.acceptedAt.format(HMS);
        }

        Timestamp arrivedTs = rs.getTimestamp("arrived_at");
        if (arrivedTs != null) {
            a.arrivedAt = arrivedTs.toInstant().atZone(ZONE).toOffsetDateTime();
        }

        Timestamp updatedTs = rs.getTimestamp("updated_at");
        if (updatedTs != null) {
            a.updatedAt = updatedTs.toInstant().atZone(ZONE).toOffsetDateTime();
        }

        a.linkageAvailable = rs.getBoolean("linkage_available");
        a.linkageFinished = rs.getBoolean("linkage_finished");
        a.linkageFailed = rs.getBoolean("linkage_failed");
        a.takeover = rs.getBoolean("takeover");

        if ("EDGE_REPLAY".equals(a.origin)) {
            DemoAlert.EdgeReplayMeta replay = new DemoAlert.EdgeReplayMeta();
            replay.edgeNodeId = rs.getString("edge_node_code");
            replay.offlineEventId = rs.getString("offline_event_id");
            replay.ruleVersionUsed = rs.getString("rule_version_used");
            replay.offlineOccurred = rs.getBoolean("offline_occurred");
            long delay = rs.getLong("sync_delay_sec");
            replay.syncDelaySec = rs.wasNull() ? null : delay;
            Timestamp syncTs = rs.getTimestamp("synced_at");
            if (syncTs != null) {
                replay.syncedAt = syncTs.toInstant().atZone(ZONE).toOffsetDateTime();
            }
            a.edgeReplay = replay;
        }

        return a;
    }

    private String resolveAreaCode(String areaName) {
        if (areaName == null || areaName.isBlank()) {
            return null;
        }
        return masterData.areas().stream()
                .filter(a -> a.name().equals(areaName) || a.code().equals(areaName))
                .map(DemoMasterData.DemoArea::code)
                .findFirst()
                .orElse(areaName);
    }

    private String resolveUserCode(String userName) {
        if (userName == null || userName.isBlank() || "待分配".equals(userName)) {
            return null;
        }
        return masterData.users().stream()
                .filter(u -> userName.contains(u.name()) || userName.equals(u.id()))
                .map(DemoMasterData.DemoUser::id)
                .findFirst()
                .orElse(null);
    }

    private String resolveEvidenceType(AlertEvidence ev) {
        if (ev instanceof AlertEvidence.PersonnelEvidence) {
            return "PERSONNEL";
        } else if (ev instanceof AlertEvidence.CollisionEvidence) {
            return "COLLISION";
        } else if (ev instanceof AlertEvidence.AiEvidence) {
            return "AI";
        } else if (ev instanceof AlertEvidence.MetricEvidence) {
            return "METRIC";
        }
        return "UNKNOWN";
    }

    private String resolveEvidenceSource(AlertEvidence ev) {
        if (ev instanceof AlertEvidence.PersonnelEvidence p) {
            return p.band();
        } else if (ev instanceof AlertEvidence.CollisionEvidence c) {
            return c.radar();
        } else if (ev instanceof AlertEvidence.AiEvidence a) {
            return a.camera();
        }
        return null;
    }

    private AlertEvidence deserializeEvidence(String payloadJson, String evidenceType) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            String kind = node.has("kind") ? node.get("kind").asText() : "";
            if ("personnel".equalsIgnoreCase(kind) || "PERSONNEL".equalsIgnoreCase(evidenceType)) {
                return objectMapper.treeToValue(node, AlertEvidence.PersonnelEvidence.class);
            } else if ("collision".equalsIgnoreCase(kind) || "COLLISION".equalsIgnoreCase(evidenceType)) {
                return objectMapper.treeToValue(node, AlertEvidence.CollisionEvidence.class);
            } else if ("ai".equalsIgnoreCase(kind) || "AI".equalsIgnoreCase(evidenceType)) {
                return objectMapper.treeToValue(node, AlertEvidence.AiEvidence.class);
            } else if (kind.contains("metric") || "METRIC".equalsIgnoreCase(evidenceType)) {
                return objectMapper.treeToValue(node, AlertEvidence.MetricEvidence.class);
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to deserialize evidence payload: {}", payloadJson, e);
            return null;
        }
    }

    private List<String> deserializeMeasures(String measuresJson) {
        if (measuresJson == null || measuresJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(measuresJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String deserializeAttachment(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return null;
        }
        try {
            List<String> list = objectMapper.readValue(attachmentsJson, new TypeReference<List<String>>() {});
            return list != null && !list.isEmpty() ? list.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize object to JSON", e);
        }
    }

    private PGobject toPgJsonb(String jsonStr) {
        if (jsonStr == null) {
            return null;
        }
        try {
            PGobject obj = new PGobject();
            obj.setType("jsonb");
            obj.setValue(jsonStr);
            return obj;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to construct PGobject jsonb", e);
        }
    }

    private String inferTimelineEventType(String text) {
        if (text == null) {
            return AlertTimelineEventTypes.NOTE;
        }
        if (text.contains("提交处置结果")) {
            return AlertTimelineEventTypes.TREATMENT_SUBMITTED;
        }
        if (text.contains("复核驳回")) {
            return AlertTimelineEventTypes.REVIEW_REJECTED;
        }
        if (text.contains("复核通过") || text.contains("事件关闭") || text.contains("风险解除，事件关闭")) {
            return AlertTimelineEventTypes.CLOSED;
        }
        if (text.contains("升级为")) {
            return AlertTimelineEventTypes.ESCALATED;
        }
        if (text.contains("已派发")) {
            return AlertTimelineEventTypes.ASSIGNED;
        }
        if (text.contains("转派")) {
            return AlertTimelineEventTypes.TRANSFERRED;
        }
        if (text.contains("到达现场") || text.contains("已到达")) {
            return AlertTimelineEventTypes.ARRIVED;
        }
        if (text.contains("接单") || text.contains("赶赴现场")) {
            return AlertTimelineEventTypes.STARTED;
        }
        if (text.contains("确认事件") || text.contains("核实事件") || text.contains("李娜确认") || text.contains("确认并")) {
            return AlertTimelineEventTypes.CONFIRMED;
        }
        if (text.contains("人工接管")) {
            return AlertTimelineEventTypes.TAKEOVER;
        }
        if (text.contains("联动") || text.contains("PLC")) {
            return AlertTimelineEventTypes.LINKAGE_STARTED;
        }
        if (text.contains("生成") && (text.contains("告警") || text.contains("预警"))) {
            return AlertTimelineEventTypes.CREATED;
        }
        return AlertTimelineEventTypes.NOTE;
    }
}
