package com.bproject.safety.module.fence.dto;

import com.bproject.safety.module.fence.model.FencePoint;
import java.util.List;

/** 围栏请求 DTO（Backend Demo 轻量表单，不做低代码规则编辑器）。 */
public final class FenceRequests {

    private FenceRequests() {
    }

    public record CreateFenceRequest(String name, String kind, String riskLevel, String teams,
                                     String startsAt, String endsAt, List<FencePoint> polygon,
                                     Boolean submitReview) {
    }

    public record UpdateFenceRequest(String name, String kind, String teams, String riskLevel,
                                     String startsAt, String endsAt, List<FencePoint> polygon) {
    }

    public record PublishRequest(List<String> nodeIds) {
    }

    public record RedeliverRequest(String nodeId) {
    }
}
