package com.bproject.safety.infrastructure.mq;

import com.bproject.safety.infrastructure.health.InfrastructureProbe;
import com.bproject.safety.infrastructure.health.ProbeResult;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 连接探针：通过 Admin 向 NameServer 查询集群信息验证连通性。
 * 未启用（app.rocketmq.enabled=false）时返回 DISABLED，不影响就绪判定。
 */
@Component
public class RocketMqProbe implements InfrastructureProbe {
    private final ObjectProvider<RocketMqAdapter> adapterProvider;

    public RocketMqProbe(ObjectProvider<RocketMqAdapter> adapterProvider) {
        this.adapterProvider = adapterProvider;
    }

    @Override
    public String componentName() {
        return "rocketmq";
    }

    @Override
    public ProbeResult probe() {
        RocketMqAdapter adapter = adapterProvider.getIfAvailable();
        if (adapter == null) {
            return ProbeResult.disabled("RocketMQ 未启用 (app.rocketmq.enabled=false)");
        }
        try {
            ClusterInfo clusterInfo = adapter.admin().examineBrokerClusterInfo();
            int brokerCount = clusterInfo == null || clusterInfo.getBrokerAddrTable() == null
                    ? 0 : clusterInfo.getBrokerAddrTable().size();
            return ProbeResult.up("NameServer 可达，broker 集群数=" + brokerCount
                    + "，producerStarted=" + adapter.isProducerStarted());
        } catch (Exception ex) {
            return ProbeResult.down(rootCause(ex));
        }
    }

    private String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
