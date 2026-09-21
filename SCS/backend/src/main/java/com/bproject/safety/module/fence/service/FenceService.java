package com.bproject.safety.module.fence.service;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.module.fence.dto.FenceRequests.CreateFenceRequest;
import com.bproject.safety.module.fence.dto.FenceRequests.UpdateFenceRequest;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.EdgeNode;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.module.fence.repository.FenceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 电子围栏业务服务（Backend Demo）：CRUD / 评审 / 发布（边缘下发）/ 版本异常 / 重新下发 / 停用。
 * 不连接真实边缘节点，EDGE 同步为 Demo 状态机；每次变更广播 fence.changed。
 */
@Service
public class FenceService {

    private static final List<String> KINDS =
            List.of("危险区域", "设备区域", "临时围栏", "预警区域", "授权区域");

    private final FenceRepository repository;
    private final DomainLivePublisher publisher;
    private final Clock clock;
    private final FenceNumberGenerator numberGenerator;

    public FenceService(FenceRepository repository, DomainLivePublisher publisher, Clock clock,
                        FenceNumberGenerator numberGenerator) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.numberGenerator = numberGenerator;
    }

    public List<DemoFence> list(String keyword, String status, String kind) {
        String kw = keyword == null ? "" : keyword.trim();
        return repository.findAll().stream().filter(f ->
                (kw.isEmpty() || f.name.contains(kw) || f.id.contains(kw))
                        && (status == null || status.isBlank()
                                || FenceStatuses.normalize(status).equals(f.statusCode))
                        && (kind == null || kind.isBlank() || kind.equals(f.kind))).toList();
    }

    public DemoFence get(String id) {
        return require(id);
    }

    public DemoFence create(CreateFenceRequest req) {
        validate(req.name(), req.polygon());
        if (req.kind() != null && !KINDS.contains(req.kind())) {
            throw ApiException.unprocessable("非法围栏类型: " + req.kind());
        }
        DemoFence f = new DemoFence();
        f.id = nextFenceId();
        applyBasics(f, req.name(), req.kind() == null ? "临时围栏" : req.kind(),
                req.riskLevel(), req.teams(), req.startsAt(), req.endsAt(), req.polygon());
        boolean review = Boolean.TRUE.equals(req.submitReview());
        f.version = "v1.0";
        f.statusCode = review ? FenceStatuses.TO_REVIEW : FenceStatuses.DRAFT;
        f.approver = review ? "等待审批" : "—";
        f.nodes = DemoFence.pendingNodes();
        f.edgeSynced = 0;
        OffsetDateTime now = OffsetDateTime.now(clock);
        f.createdAt = now;
        f.updatedAt = now;
        repository.save(f);
        publish(f, "create");
        return f;
    }

    public DemoFence update(String id, UpdateFenceRequest req) {
        DemoFence f = require(id);
        if (FenceStatuses.DISABLED.equals(f.statusCode)) {
            throw ApiException.conflict("已停用围栏不可编辑，请重新启用后再修改");
        }
        validate(req.name(), req.polygon());
        applyBasics(f, req.name(), req.kind(), req.riskLevel(), req.teams(), req.startsAt(), req.endsAt(),
                req.polygon());
        // 已生效围栏被编辑后回到待评审，版本递增，需要重新发布（专业配置中心口径）
        if (FenceStatuses.EFFECTIVE.equals(f.statusCode) || FenceStatuses.MISMATCH.equals(f.statusCode)) {
            f.statusCode = FenceStatuses.TO_REVIEW;
            f.version = bumpMinor(f.version);
            f.nodes = DemoFence.pendingNodes();
            f.edgeSynced = 0;
            f.approver = "等待审批";
        }
        f.updatedAt = OffsetDateTime.now(clock);
        repository.save(f);
        publish(f, "update");
        return f;
    }

    public DemoFence submitReview(String id) {
        DemoFence f = require(id);
        if (!List.of(FenceStatuses.DRAFT, FenceStatuses.TO_PUBLISH).contains(f.statusCode)) {
            throw ApiException.conflict("当前状态不允许提交评审（当前状态："
                    + FenceStatuses.label(f.statusCode) + "）");
        }
        f.statusCode = FenceStatuses.TO_REVIEW;
        f.approver = "等待审批";
        f.updatedAt = OffsetDateTime.now(clock);
        repository.save(f);
        publish(f, "submit-review");
        return f;
    }

    /** 发布：模拟 4 个边缘节点全部同步成功，状态 → 已生效。 */
    public DemoFence publish(String id) {
        DemoFence f = require(id);
        if (FenceStatuses.DISABLED.equals(f.statusCode)) {
            throw ApiException.conflict("已停用围栏不可发布");
        }
        if (FenceStatuses.EFFECTIVE.equals(f.statusCode) && f.edgeSynced == f.edgeTotal) {
            return f;
        }
        f.nodes = DemoFence.successNodes();
        f.edgeSynced = f.edgeTotal;
        f.statusCode = FenceStatuses.EFFECTIVE;
        f.effectiveAt = "立即生效";
        f.updatedAt = OffsetDateTime.now(clock);
        repository.save(f);
        publish(f, "publish");
        return f;
    }

    /** 单节点重新下发：指定节点恢复 success；全部成功后围栏恢复已生效。 */
    public DemoFence redeliver(String id, String nodeId) {
        DemoFence f = require(id);
        String target = nodeId == null || nodeId.isBlank() ? "EDGE-03" : nodeId;
        boolean exists = f.nodes.stream().anyMatch(n -> n.id().equals(target));
        if (!exists) {
            throw ApiException.unprocessable("边缘节点不存在: " + target);
        }
        f.nodes = f.nodes.stream().map(n -> n.id().equals(target) ? n.withState(EdgeNode.SUCCESS) : n).toList();
        f.edgeSynced = (int) f.nodes.stream().filter(n -> EdgeNode.SUCCESS.equals(n.state())).count();
        if (f.edgeSynced == f.edgeTotal) {
            f.statusCode = FenceStatuses.EFFECTIVE;
        }
        f.updatedAt = OffsetDateTime.now(clock);
        repository.save(f);
        publish(f, "redeliver");
        return f;
    }

    public DemoFence disable(String id) {
        DemoFence f = require(id);
        if (!List.of(FenceStatuses.EFFECTIVE, FenceStatuses.MISMATCH, FenceStatuses.TO_PUBLISH)
                .contains(f.statusCode)) {
            throw ApiException.conflict("当前状态不允许停用（当前状态："
                    + FenceStatuses.label(f.statusCode) + "）");
        }
        f.statusCode = FenceStatuses.DISABLED;
        f.nodes = f.nodes.stream().map(n -> n.withState(EdgeNode.PENDING)).toList();
        f.edgeSynced = 0;
        f.updatedAt = OffsetDateTime.now(clock);
        repository.save(f);
        publish(f, "disable");
        return f;
    }

    /** 模拟 EDGE-03 版本不一致（SIMULATED）。 */
    public DemoFence simulateMismatch(String id) {
        DemoFence f = require(id);
        f.nodes = DemoFence.successNodes();
        f.nodes = f.nodes.stream().map(n -> n.id().equals("EDGE-03") ? n.withState(EdgeNode.FAILED) : n).toList();
        f.edgeSynced = (int) f.nodes.stream().filter(n -> EdgeNode.SUCCESS.equals(n.state())).count();
        f.statusCode = FenceStatuses.MISMATCH;
        f.updatedAt = OffsetDateTime.now(clock);
        repository.save(f);
        publish(f, "simulate-mismatch");
        return f;
    }

    // ---------- 内部 ----------

    private void applyBasics(DemoFence f, String name, String kind, String risk, String teams,
                             String startsAt, String endsAt, List<FencePoint> polygon) {
        f.name = name;
        f.kind = kind;
        f.tone = toneOf(kind);
        // 请求仍可传中文风险等级，落模型统一归一为机器 code（默认 NORMAL）。
        f.riskCode = (risk == null || risk.isBlank())
                ? com.bproject.safety.module.alert.model.RiskLevels.NORMAL
                : com.bproject.safety.module.alert.model.RiskLevels.normalize(risk);
        f.area = f.area == null ? "自定义区域" : f.area;
        f.teams = teams == null ? "" : teams;
        if (startsAt != null) {
            f.effectiveAt = startsAt;
        }
        if (endsAt != null) {
            f.expiresAt = endsAt;
        }
        if (polygon != null) {
            f.polygon = List.copyOf(polygon);
        }
    }

    private void validate(String name, List<FencePoint> polygon) {
        if (name == null || name.isBlank()) {
            throw ApiException.unprocessable("围栏名称不能为空");
        }
        if (polygon == null || polygon.size() < 3) {
            throw ApiException.unprocessable("围栏边界至少需要 3 个顶点");
        }
        polygon.forEach(p -> {
            if (p == null || p.x() < 0 || p.x() > 100 || p.y() < 0 || p.y() > 100) {
                throw ApiException.unprocessable("围栏顶点坐标必须在 0~100 百分比范围内");
            }
        });
    }

    private static String toneOf(String kind) {
        return switch (kind) {
            case "危险区域" -> "danger";
            case "预警区域" -> "warning";
            case "临时围栏" -> "temporary";
            default -> "normal";
        };
    }

    /** Phase B：编号生成委托 {@link FenceNumberGenerator}，格式 FENCE-NNN 不变。 */
    private String nextFenceId() {
        return numberGenerator.nextFenceNumber();
    }

    private static String bumpMinor(String version) {
        try {
            int minor = Integer.parseInt(version.replaceAll("[^0-9.]", "").split("\\.")[1]);
            return "v" + version.replaceAll("[^0-9.]", "").split("\\.")[0] + "." + (minor + 1);
        } catch (RuntimeException ex) {
            return "v1.1";
        }
    }

    private DemoFence require(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("围栏不存在: " + id));
    }

    private void publish(DemoFence f, String op) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fenceId", f.id);
        data.put("status", f.getStatus());
        data.put("statusCode", f.statusCode);
        data.put("version", f.version);
        data.put("edgeSynced", f.edgeSynced);
        data.put("edgeTotal", f.edgeTotal);
        data.put("changeType", op);
        publisher.publish(LiveEventTypes.FENCE_CHANGED, data);
    }
}
