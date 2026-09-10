package com.bproject.safety.module.ops.realtime;

import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.module.ops.model.DemoEdgeNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 运维实时事件通知（任务书第二十八节）：只发送 nodeId / status / cloudConnected /
 * queueDepth / phase / errorCode 等轻量摘要，不推完整日志与对象，前端收到后按模块 REST 重拉。
 */
@Component
public class OpsLiveNotifier {

    private final DomainLivePublisher publisher;

    public OpsLiveNotifier(DomainLivePublisher publisher) {
        this.publisher = publisher;
    }

    public void nodeChanged(DemoEdgeNode node, String errorCode) {
        Map<String, Object> data = base(node);
        if (errorCode != null) {
            data.put("errorCode", errorCode);
        }
        publisher.publish(LiveEventTypes.OPS_NODE_CHANGED, data);
    }

    public void syncChanged(DemoEdgeNode node) {
        Map<String, Object> data = base(node);
        data.put("clockOffsetMs", node.clockOffsetMs);
        data.put("activeRuleVersion", node.activeRuleVersion);
        data.put("expectedRuleVersion", node.expectedRuleVersion);
        publisher.publish(LiveEventTypes.OPS_SYNC_CHANGED, data);
    }

    public void queueChanged(DemoEdgeNode node) {
        Map<String, Object> data = base(node);
        data.put("queueDepth", node.queueDepth);
        data.put("cachedEventCount", node.cachedEventCount);
        publisher.publish(LiveEventTypes.OPS_QUEUE_CHANGED, data);
    }

    public void recoveryChanged(DemoEdgeNode node, String phase) {
        Map<String, Object> data = base(node);
        data.put("phase", phase);
        publisher.publish(LiveEventTypes.OPS_RECOVERY_CHANGED, data);
    }

    private Map<String, Object> base(DemoEdgeNode node) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", node.id);
        data.put("status", node.status);
        data.put("cloudConnected", node.cloudConnected);
        data.put("queueDepth", node.queueDepth);
        return data;
    }
}
